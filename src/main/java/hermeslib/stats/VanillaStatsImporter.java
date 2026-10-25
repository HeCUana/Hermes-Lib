package hermeslib.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hermeslib.api.Metric;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 原版统计文件的扫描与解析。
 *
 * <p><b>只做纯 IO 与解析，不碰内存也不碰数据库</b>，
 * 由 {@link HermesStatsManager#importVanillaStats()} 在异步线程上调用。</p>
 *
 * <p>原版统计文件位置：{@code <世界>/stats/<UUID>.json}，
 * 结构为 {@code {"stats": {"<类型>": {"<key>": 数值}}, "DataVersion": N}}。</p>
 */
public final class VanillaStatsImporter
{
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /** 单个玩家的导入结果。 */
    public record PlayerImport(UUID uuid, String name, Map<Metric, Long> values)
    {
    }

    /** 一次扫描的总体结果。 */
    public record Outcome(List<PlayerImport> players, int skipped, List<String> errors)
    {
    }

    private VanillaStatsImporter()
    {
    }

    /**
     * 扫描统计目录并解析所有玩家文件。
     *
     * @param statsDir 原版统计目录（{@code <世界>/stats}）
     * @param names    已知的「UUID → 玩家名」，可为 null
     */
    public static Outcome scan(Path statsDir, Map<UUID, String> names)
    {
        List<PlayerImport> players = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;

        if (statsDir == null || !Files.isDirectory(statsDir))
        {
            errors.add("原版统计目录不存在：" + statsDir);
            return new Outcome(players, skipped, errors);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(statsDir))
        {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
        }
        catch (IOException e)
        {
            errors.add("无法列出统计目录：" + e.getMessage());
            return new Outcome(players, skipped, errors);
        }

        for (Path file : files)
        {
            String fileName = file.getFileName().toString();
            UUID uuid;
            try
            {
                uuid = UUID.fromString(fileName.substring(0, fileName.length() - ".json".length()));
            }
            catch (IllegalArgumentException e)
            {
                skipped++;
                errors.add("文件名不是合法 UUID，已跳过：" + fileName);
                continue;
            }

            try
            {
                Map<Metric, Long> values = parse(file);
                if (values.isEmpty())
                {
                    skipped++;
                    errors.add("没有可识别的统计项，已跳过：" + fileName);
                    continue;
                }
                String name = names == null ? null : names.get(uuid);
                players.add(new PlayerImport(uuid, name, values));
            }
            catch (Exception e)
            {
                skipped++;
                errors.add("解析失败，已跳过：" + fileName + "（" + e.getMessage() + "）");
            }
        }

        return new Outcome(players, skipped, errors);
    }

    /**
     * 读取原版玩家档案缓存，用于把 UUID 还原成玩家名。
     *
     * <p>文件位于服务端根目录（世界目录的上一级）：
     * 独立服为 {@code <服务端>/usercache.json}，单人游戏为 {@code <存档>/usercache.json}。</p>
     *
     * <p>拿不到就返回空表 —— 导入不依赖名字，不应因此失败。</p>
     */
    public static Map<UUID, String> readUserCache(Path serverRoot)
    {
        Map<UUID, String> names = new HashMap<>();
        if (serverRoot == null) return names;
        Path file = serverRoot.resolve("usercache.json");
        if (!Files.isRegularFile(file)) return names;

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonArray()) return names;
            for (JsonElement element : root.getAsJsonArray())
            {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("uuid") || !object.has("name")) continue;
                try
                {
                    names.put(UUID.fromString(object.get("uuid").getAsString()), object.get("name").getAsString());
                }
                catch (RuntimeException ignored)
                {
                    // 脏数据，跳过
                }
            }
        }
        catch (Exception e)
        {
            // 拿不到名字不阻塞导入
            return names;
        }
        return names;
    }

    /** 解析单个统计文件，返回「指标 → 数值」。 */
    private static Map<Metric, Long> parse(Path file) throws IOException
    {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject())
            {
                throw new IOException("不是合法的 JSON 对象");
            }
            root = element.getAsJsonObject();
        }

        if (!root.has("stats") || !root.get("stats").isJsonObject())
        {
            throw new IOException("缺少 stats 字段（版本不兼容）");
        }
        JsonObject stats = root.getAsJsonObject("stats");

        Map<Metric, Long> result = new EnumMap<>(Metric.class);
        for (Map.Entry<String, JsonElement> typeEntry : stats.entrySet())
        {
            String type = typeEntry.getKey();
            JsonElement typeValue = typeEntry.getValue();
            if (typeValue == null || !typeValue.isJsonObject()) continue;
            JsonObject typeObject = typeValue.getAsJsonObject();

            // custom 类：逐 key 一对一映射
            if (VanillaStatMapping.isCustomType(type))
            {
                for (Map.Entry<String, JsonElement> entry : typeObject.entrySet())
                {
                    Optional<Metric> metric = VanillaStatMapping.customMetric(entry.getKey());
                    if (metric.isEmpty()) continue;   // 未识别的统计项：跳过，不报错
                    long value = asLong(entry.getValue());
                    if (value != 0L)
                    {
                        result.merge(metric.get(), value, Long::sum);
                    }
                }
                continue;
            }

            // 按物品种类拆分的类：遍历求和后写入单一指标
            if (VanillaStatMapping.summedMetric(type).isPresent())
            {
                Metric metric = VanillaStatMapping.summedMetric(type).get();
                long sum = 0L;
                for (Map.Entry<String, JsonElement> entry : typeObject.entrySet())
                {
                    sum += asLong(entry.getValue());
                }
                if (sum != 0L)
                {
                    result.merge(metric, sum, Long::sum);
                }
            }

            // 其余类型（未识别的统计项）：跳过，不报错
        }
        return result;
    }

    /** 从 JSON 值里取 long（兼容数字与数字字符串）。 */
    private static long asLong(JsonElement element)
    {
        if (element == null || !element.isJsonPrimitive()) return 0L;
        try
        {
            return element.getAsLong();
        }
        catch (RuntimeException e)
        {
            try
            {
                return (long) Double.parseDouble(element.getAsString());
            }
            catch (RuntimeException ignored)
            {
                return 0L;
            }
        }
    }
}
