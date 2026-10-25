package themislib.stats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import themislib.api.DataDomain;
import themislib.api.Metric;
import themislib.api.RankingDataProvider;
import themislib.ThemisLib;
import themislib.rules.ThemisRules;
import themislib.storage.DatabaseManager;
import themislib.storage.DatabaseManager.StatRow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存统计中心：所有采集事件的唯一入口，也是对外 API 的实现。
 *
 * <p><b>线程模型</b></p>
 * <ul>
 *   <li>所有 {@code add(...)} 调用来自服务器主线程（Mixin 回调），只改内存；</li>
 *   <li>落库时先在主线程拍一份不可变快照，再把快照提交给 SQLite 线程；</li>
 *   <li>游戏主线程<b>永不</b>直接操作 JDBC。</li>
 * </ul>
 */
public final class ThemisStatsManager implements RankingDataProvider
{
    /** 定期落库间隔：5 分钟（6000 tick）。 */
    private static final int FLUSH_INTERVAL_TICKS = 20 * 60 * 5;

    /** 单 tick 超过该距离视为传送，不计入移动统计。 */
    private static final double MAX_PER_TICK_DISTANCE = 50.0D;

    /** 附魔归属的时间窗口：10 秒。 */
    private static final long ENCHANT_ATTRIBUTION_WINDOW = 10_000L;

    private static volatile ThemisStatsManager instance;

    private final ConcurrentHashMap<StatKey, PlayerStats> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPositions = new ConcurrentHashMap<>();

    /** 最近一次使用附魔台/铁砧的玩家（用于给「附魔次数」归属到玩家）。 */
    private final ConcurrentHashMap<UUID, Long> enchantUsers = new ConcurrentHashMap<>();

    private volatile DatabaseManager database;
    private int tickCounter;

    // ==================== 生命周期 ====================

    /** 安装全局实例（Mixin 通过静态方法访问）。 */
    public static void install(ThemisStatsManager manager)
    {
        instance = manager;
    }

    /** 卸载全局实例。 */
    public static void uninstall()
    {
        instance = null;
    }

    /** 获取全局实例；服务器尚未启动时返回 null（所有调用方都做了空安全处理）。 */
    public static ThemisStatsManager get()
    {
        return instance;
    }

    /**
     * 打开数据库并把历史数据读进内存。
     *
     * @param server 当前服务器
     */
    public void start(MinecraftServer server)
    {
        // 数据库放在世界目录下，随世界一起保存/迁移
        Path dbFile = server.getWorldPath(LevelResource.ROOT).resolve("ranking-stats.db");
        DatabaseManager manager = new DatabaseManager(dbFile);
        manager.init();
        this.database = manager;
        loadFromDatabase();
    }

    /** 服务器关闭：落库 + 关闭连接。 */
    public void shutdown()
    {
        flushAll();
        DatabaseManager manager = this.database;
        this.database = null;
        if (manager != null)
        {
            manager.close();
        }
        uninstall();
    }

    // ==================== 写入 ====================

    /**
     * 累加一个指标（对外给 Mixin 调用的主入口）。
     *
     * <p>会自动完成：总开关判断 → 指标开关判断 → 假人识别 → 数据域分流 → UUID 归一。</p>
     */
    public void add(Player player, Metric metric, long amount)
    {
        if (player == null || amount == 0L) return;
        if (!isMetricEnabled(metric)) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;  // 只统计服务端玩家

        UUID uuid = FakePlayerDetector.effectiveUuid(serverPlayer);
        DataDomain domain = FakePlayerDetector.domainOf(serverPlayer);
        addRaw(uuid, domain, metric, amount);

        String name = playerName(serverPlayer);
        if (name != null && !name.equals(names.get(uuid)))
        {
            names.put(uuid, name);
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        }
    }

    /** 累加一个指标（已知 UUID 与数据域时使用）。 */
    public void addRaw(UUID uuid, DataDomain domain, Metric metric, long amount)
    {
        if (uuid == null || amount == 0L) return;
        if (!isMetricEnabled(metric)) return;
        data.computeIfAbsent(new StatKey(uuid, domain), key -> new PlayerStats()).add(metric, amount);
        if (ThemisLib.DEBUG)
        {
            ThemisLib.LOG.info("[Themis][debug] 采集 {} +{} -> uuid={} domain={}",
                    metric.dbName(), amount, uuid, domain);
        }
    }

    /** 记录某个玩家刚刚使用了附魔台/铁砧，供后续附魔事件归属。 */
    public void recordEnchantUser(ServerPlayer player)
    {
        if (player == null) return;
        enchantUsers.put(player.getUUID(), System.currentTimeMillis());
    }

    /** 记一次附魔：归属到最近使用过附魔台/铁砧的玩家。 */
    public void addEnchantment()
    {
        if (!isMetricEnabled(Metric.ENCHANTMENTS)) return;
        long now = System.currentTimeMillis();
        UUID best = null;
        long bestTime = 0L;
        for (Map.Entry<UUID, Long> entry : enchantUsers.entrySet())
        {
            long ts = entry.getValue();
            if (now - ts <= ENCHANT_ATTRIBUTION_WINDOW && ts >= bestTime)
            {
                best = entry.getKey();
                bestTime = ts;
            }
        }
        if (best == null) return;
        addRaw(best, DataDomain.REAL, Metric.ENCHANTMENTS, 1L);
    }

    /** 指标是否处于开启状态（总开关 + 单项开关）。 */
    public static boolean isMetricEnabled(Metric metric)
    {
        if (!ThemisRules.rankingEnabled) return false;
        switch (metric)
        {
            case PLAYTIME_TICKS:
            case PLAYTIME_SECONDS:
                return ThemisRules.rankingTrackPlayTime;
            case BLOCKS_MINED:
                return ThemisRules.rankingTrackBlocksMined;
            case BLOCKS_PLACED:
                return ThemisRules.rankingTrackBlocksPlaced;
            case MOB_KILLS:
                return ThemisRules.rankingTrackMobKills;
            case PLAYER_KILLS:
                return ThemisRules.rankingTrackPlayerKills;
            case DEATHS:
                return ThemisRules.rankingTrackDeaths;
            case VILLAGER_TRADES:
                return ThemisRules.rankingTrackVillagerTrades;
            case FISH_CAUGHT:
                return ThemisRules.rankingTrackFishCaught;
            case DISTANCE_ELYTRA:
                return ThemisRules.rankingTrackElytraDistance;
            case DAMAGE_DEALT:
                return ThemisRules.rankingTrackDamageDealt;
            case DAMAGE_TAKEN:
                return ThemisRules.rankingTrackDamageTaken;
            case DISTANCE_WALK:
            case DISTANCE_SPRINT:
            case DISTANCE_SWIM:
            case DISTANCE_RIDE:
            case DISTANCE_TOTAL:
                return ThemisRules.rankingTrackMovement;
            case ITEMS_CRAFTED:
                return ThemisRules.rankingTrackItemsCrafted;
            case ITEMS_PICKED_UP:
                return ThemisRules.rankingTrackItemsPickedUp;
            case ITEMS_DROPPED:
                return ThemisRules.rankingTrackItemsDropped;
            case ITEMS_USED:
                return ThemisRules.rankingTrackItemsUsed;
            case ITEMS_DAMAGED:
            case ITEMS_BROKEN:
                return ThemisRules.rankingTrackItemsBroken;
            case ANIMALS_BRED:
                return ThemisRules.rankingTrackAnimalsBred;
            case ANIMALS_TAMED:
                return ThemisRules.rankingTrackAnimalsTamed;
            case ENCHANTMENTS:
                return ThemisRules.rankingTrackEnchantments;
            case JUMPS:
                return ThemisRules.rankingTrackJumps;
            default:
                return true;
        }
    }

    // ==================== 每 tick 采集 ====================

    /**
     * 每 tick 调用一次（由 Carpet 的 tick 钩子驱动）。
     *
     * <p>负责：在线时长、移动距离、以及定期落库。</p>
     */
    public void tick(MinecraftServer server)
    {
        if (!ThemisRules.rankingEnabled) return;
        tickCounter++;

        forEachOnlinePlayer(server, this::recordPlayerTick);

        if (tickCounter % FLUSH_INTERVAL_TICKS == 0)
        {
            flushAll();
            PlacedBlockTracker.prune(System.currentTimeMillis());
            InteractionTracker.prune();
        }
        else if (ThemisLib.DEBUG && tickCounter % 100 == 0)
        {
            ThemisLib.LOG.info("[Themis][debug] tick={} 数据条目={}", tickCounter, data.size());
        }
    }

    /**
     * 遍历所有在线玩家（含假人）。
     *
     * <p>刻意同时取两个来源：</p>
     * <ul>
     *   <li>{@code PlayerList#getPlayers()} —— 在线玩家列表；</li>
     *   <li>各维度的玩家实体 —— Carpet 假人等「不一定出现在在线列表里」的玩家仍然活在关卡中。</li>
     * </ul>
     *
     * <p>用 UUID 去重，避免同一玩家被统计两次。</p>
     */
    private static void forEachOnlinePlayer(MinecraftServer server, java.util.function.Consumer<ServerPlayer> consumer)
    {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (seen.add(player.getUUID())) consumer.accept(player);
        }
        for (ServerLevel level : server.getAllLevels())
        {
            for (ServerPlayer player : level.getPlayers(candidate -> true))
            {
                if (seen.add(player.getUUID())) consumer.accept(player);
            }
        }
    }

    private void recordPlayerTick(ServerPlayer player)
    {
        recordPlayTime(player);
        recordMovement(player);
    }

    private void recordPlayTime(ServerPlayer player)
    {
        if (!ThemisRules.rankingTrackPlayTime) return;
        add(player, Metric.PLAYTIME_TICKS, 1L);
        if (tickCounter % 20 == 0)
        {
            add(player, Metric.PLAYTIME_SECONDS, 1L);
        }
    }

    private void recordMovement(ServerPlayer player)
    {
        if (!ThemisRules.rankingTrackMovement) return;

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        UUID uuid = player.getUUID();

        double[] last = lastPositions.get(uuid);
        if (last == null)
        {
            lastPositions.put(uuid, new double[]{ x, y, z });
            return;
        }
        double dx = x - last[0];
        double dy = y - last[1];
        double dz = z - last[2];
        last[0] = x;
        last[1] = y;
        last[2] = z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 1.0E-6D) return;
        // 传送 / 维度切换产生的瞬移不算移动距离
        if (horizontal > MAX_PER_TICK_DISTANCE) return;

        long centiBlocks = Math.round(horizontal * 100.0D);

        Metric bucket;
        if (player.isFallFlying())
        {
            bucket = Metric.DISTANCE_ELYTRA;
        }
        else if (player.isPassenger())
        {
            bucket = Metric.DISTANCE_RIDE;
        }
        else if (player.isSwimming() || player.isInWater())
        {
            bucket = Metric.DISTANCE_SWIM;
        }
        else if (player.isSprinting())
        {
            bucket = Metric.DISTANCE_SPRINT;
        }
        else
        {
            bucket = Metric.DISTANCE_WALK;
        }

        add(player, bucket, centiBlocks);
        add(player, Metric.DISTANCE_TOTAL, centiBlocks);
    }

    // ==================== 玩家进出 ====================

    public void onJoin(ServerPlayer player)
    {
        lastPositions.put(player.getUUID(), new double[]{ player.getX(), player.getY(), player.getZ() });
        String name = playerName(player);
        if (name != null)
        {
            names.put(player.getUUID(), name);
            nameIndex.put(name.toLowerCase(Locale.ROOT), player.getUUID());
        }
    }

    public void onQuit(ServerPlayer player)
    {
        lastPositions.remove(player.getUUID());
        flushPlayer(player.getUUID());
    }

    // ==================== 落库 ====================

    /** 全量落库（内存里的值是绝对值，UPSERT 幂等）。 */
    public void flushAll()
    {
        DatabaseManager manager = this.database;
        if (manager == null) return;

        List<StatRow> rows = new ArrayList<>();
        for (Map.Entry<StatKey, PlayerStats> entry : data.entrySet())
        {
            StatKey key = entry.getKey();
            for (Map.Entry<Metric, Long> metric : entry.getValue().snapshot().entrySet())
            {
                rows.add(new StatRow(key.uuid().toString(), key.domain().id(), metric.getKey().dbName(), metric.getValue()));
            }
        }
        manager.upsert(rows, names);
        if (ThemisLib.DEBUG)
        {
            ThemisLib.LOG.info("[Themis][debug] 落库(周期) 行数={} 玩家数={}", rows.size(), data.size());
        }
    }

    /** 只落库某个玩家的数据（玩家退出时调用）。 */
    public void flushPlayer(UUID uuid)
    {
        DatabaseManager manager = this.database;
        if (manager == null || uuid == null) return;

        List<StatRow> rows = new ArrayList<>();
        for (Map.Entry<StatKey, PlayerStats> entry : data.entrySet())
        {
            if (!entry.getKey().uuid().equals(uuid)) continue;
            for (Map.Entry<Metric, Long> metric : entry.getValue().snapshot().entrySet())
            {
                rows.add(new StatRow(uuid.toString(), entry.getKey().domain().id(), metric.getKey().dbName(), metric.getValue()));
            }
        }
        Map<UUID, String> nameOf = new HashMap<>();
        String name = names.get(uuid);
        if (name != null)
        {
            nameOf.put(uuid, name);
        }
        if (ThemisLib.DEBUG)
        {
            ThemisLib.LOG.info("[Themis][debug] 落库(玩家退出) uuid={} 行数={}", uuid, rows.size());
        }
        manager.upsert(rows, nameOf);
    }

    /** 从数据库载入历史数据。 */
    public void loadFromDatabase()
    {
        DatabaseManager manager = this.database;
        if (manager == null) return;

        for (StatRow row : manager.loadAll())
        {
            UUID uuid;
            try
            {
                uuid = UUID.fromString(row.uuid());
            }
            catch (IllegalArgumentException e)
            {
                continue;  // 脏数据
            }
            DataDomain domain = DataDomain.byId(row.domain()).orElse(DataDomain.REAL);
            Metric metric = Metric.byName(row.metric()).orElse(null);
            if (metric == null) continue;  // 未知指标（可能来自更新版本），跳过
            data.computeIfAbsent(new StatKey(uuid, domain), key -> new PlayerStats()).add(metric, row.value());
        }

        for (Map.Entry<UUID, String> entry : manager.loadNames().entrySet())
        {
            names.put(entry.getKey(), entry.getValue());
            nameIndex.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getKey());
        }
    }

    // ==================== 对外 API 实现 ====================

    @Override
    public long getValue(UUID uuid, DataDomain domain, Metric metric)
    {
        if (uuid == null || domain == null || metric == null) return 0L;
        PlayerStats stats = data.get(new StatKey(uuid, domain));
        return stats == null ? 0L : stats.get(metric);
    }

    @Override
    public long getValue(UUID uuid, DataDomain domain, String metric)
    {
        return Metric.byName(metric).map(m -> getValue(uuid, domain, m)).orElse(0L);
    }

    @Override
    public Map<Metric, Long> getAll(UUID uuid, DataDomain domain)
    {
        if (uuid == null || domain == null) return Map.of();
        PlayerStats stats = data.get(new StatKey(uuid, domain));
        return stats == null ? Map.of() : stats.snapshot();
    }

    @Override
    public long getTotal(UUID uuid, DataDomain domain)
    {
        if (uuid == null || domain == null) return 0L;
        PlayerStats stats = data.get(new StatKey(uuid, domain));
        return stats == null ? 0L : stats.total();
    }

    @Override
    public List<RankedEntry> top(Metric metric, DataDomain domain, int limit)
    {
        if (metric == null || domain == null || limit <= 0) return List.of();
        List<RankedEntry> result = new ArrayList<>();
        for (Map.Entry<StatKey, PlayerStats> entry : data.entrySet())
        {
            if (entry.getKey().domain() != domain) continue;
            long value = entry.getValue().get(metric);
            if (value <= 0L) continue;
            UUID uuid = entry.getKey().uuid();
            result.add(new RankedEntry(uuid, names.getOrDefault(uuid, uuid.toString()), value));
        }
        result.sort(Comparator.comparingLong(RankedEntry::value).reversed()
                .thenComparing(RankedEntry::name, String.CASE_INSENSITIVE_ORDER));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    @Override
    public Optional<String> getName(UUID uuid)
    {
        return uuid == null ? Optional.empty() : Optional.ofNullable(names.get(uuid));
    }

    @Override
    public Optional<UUID> findByName(String name)
    {
        if (name == null) return Optional.empty();
        UUID found = nameIndex.get(name.toLowerCase(Locale.ROOT));
        return found == null ? Optional.empty() : Optional.of(found);
    }

    @Override
    public DataDomain domainOf(Player player)
    {
        return FakePlayerDetector.domainOf(player);
    }

    @Override
    public UUID effectiveUuid(Player player)
    {
        return FakePlayerDetector.effectiveUuid(player);
    }

    @Override
    public Collection<UUID> knownPlayers()
    {
        Set<UUID> result = new HashSet<>();
        for (StatKey key : data.keySet())
        {
            result.add(key.uuid());
        }
        return result;
    }

    // ==================== 工具 ====================

    private static String playerName(Player player)
    {
        try
        {
            if (player.getGameProfile() != null)
            {
                // MC 26.3 的 authlib 10 用 GameProfile#name()（旧版本是 getName()）
                return player.getGameProfile().name();
            }
        }
        catch (Throwable ignored)
        {
            // 取不到就算了
        }
        return null;
    }

    /** 供扩展/调试用：当前服务器里的在线玩家数量。 */
    public static int onlineCount(MinecraftServer server)
    {
        return server == null ? 0 : server.getPlayerList().getPlayerCount();
    }

    /** 供扩展/调试用：某个维度在线的假人数量。 */
    public static long fakeCount(ServerLevel level)
    {
        if (level == null) return 0L;
        return level.getPlayers(player -> true).stream().filter(FakePlayerDetector::isFake).count();
    }
}
