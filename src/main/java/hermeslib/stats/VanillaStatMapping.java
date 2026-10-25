package hermeslib.stats;

import hermeslib.api.Metric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 原版统计（{@code <世界>/stats/<UUID>.json}）到 Hermes 指标的映射表。
 *
 * <p>原版统计分两种组织方式：</p>
 * <ul>
 *   <li><b>custom 类</b>：{@code stats."minecraft:custom"} 下每个 key 就是一个独立计数，
 *       例如 {@code minecraft:play_time}、{@code minecraft:mob_kills}，与指标<b>一对一</b>；</li>
 *   <li><b>按物品种类拆分的类</b>：{@code minecraft:mined} / {@code minecraft:picked_up} /
 *       {@code minecraft:crafted} / {@code minecraft:dropped} / {@code minecraft:used}
 *       下每个 key 是一个物品，需要<b>遍历求和</b>后写入单一指标。</li>
 * </ul>
 *
 * <p>未识别的统计项直接跳过，不报错 —— 原版会随版本新增统计项，导入必须向前兼容。</p>
 *
 * <p>注意：原版没有「交易次数」「钓获次数」「繁殖/驯服次数」「附魔次数」「工具损坏数」
 * 这些统计项，因此这些指标在导入时不会被覆盖，仍以 Hermes 自身采集的值为准。</p>
 */
public final class VanillaStatMapping
{
    /** 原版「单值统计」所在的类型名，其下的每个 key 都是一个独立计数。 */
    public static final String CUSTOM_TYPE = "minecraft:custom";

    /** 原版 custom 类的 key → 指标（一对一）。 */
    private static final Map<String, Metric> CUSTOM = buildCustom();

    /** 原版按物品种类拆分的统计类型 → 指标（遍历求和）。 */
    private static final Map<String, Metric> SUMMED = buildSummed();

    private VanillaStatMapping()
    {
    }

    private static Map<String, Metric> buildCustom()
    {
        Map<String, Metric> map = new LinkedHashMap<>();
        // —— 活跃类 ——
        map.put("minecraft:play_time", Metric.PLAYTIME_TICKS);
        map.put("minecraft:jump", Metric.JUMPS);
        // —— 战斗类 ——
        map.put("minecraft:mob_kills", Metric.MOB_KILLS);
        map.put("minecraft:player_kills", Metric.PLAYER_KILLS);
        map.put("minecraft:deaths", Metric.DEATHS);
        map.put("minecraft:damage_dealt", Metric.DAMAGE_DEALT);
        map.put("minecraft:damage_taken", Metric.DAMAGE_TAKEN);
        // —— 探索类（原版用「厘米」计步数，与 Hermes 的距离单位一致） ——
        map.put("minecraft:walk_one_cm", Metric.DISTANCE_WALK);
        map.put("minecraft:swim_one_cm", Metric.DISTANCE_SWIM);
        map.put("minecraft:aviate_one_cm", Metric.DISTANCE_ELYTRA);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Metric> buildSummed()
    {
        Map<String, Metric> map = new LinkedHashMap<>();
        map.put("minecraft:mined", Metric.BLOCKS_MINED);
        map.put("minecraft:picked_up", Metric.ITEMS_PICKED_UP);
        map.put("minecraft:crafted", Metric.ITEMS_CRAFTED);
        map.put("minecraft:dropped", Metric.ITEMS_DROPPED);
        map.put("minecraft:used", Metric.ITEMS_USED);
        return Collections.unmodifiableMap(map);
    }

    /**
     * 判断某个统计类型是否是「单值统计」类型（{@code minecraft:custom}）。
     */
    public static boolean isCustomType(String statType)
    {
        return CUSTOM_TYPE.equals(normalize(statType));
    }

    /**
     * 原版 custom 类中某个 key 对应的指标。
     *
     * @param key 原版统计 key，如 {@code minecraft:play_time}
     */
    public static Optional<Metric> customMetric(String key)
    {
        return Optional.ofNullable(CUSTOM.get(normalize(key)));
    }

    /**
     * 原版某个统计类型对应的指标（需要遍历求和的类型）。
     *
     * @param statType 统计类型，如 {@code minecraft:mined}
     */
    public static Optional<Metric> summedMetric(String statType)
    {
        return Optional.ofNullable(SUMMED.get(normalize(statType)));
    }

    /** 全部一对一映射（只读）。 */
    public static Map<String, Metric> customMappings()
    {
        return CUSTOM;
    }

    /** 全部求和映射（只读）。 */
    public static Map<String, Metric> summedMappings()
    {
        return SUMMED;
    }

    /**
     * 补全命名空间前缀。
     *
     * <p>很老版本（原版 1.2 之前）的统计类型没有 {@code minecraft:} 前缀，这里统一补上。</p>
     */
    private static String normalize(String key)
    {
        if (key == null) return "";
        return key.indexOf(':') >= 0 ? key : "minecraft:" + key;
    }
}
