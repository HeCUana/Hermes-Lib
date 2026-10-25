package hermeslib.stats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import hermeslib.HermesLibMod;
import hermeslib.api.DataDomain;
import hermeslib.api.ImportResult;
import hermeslib.api.Metric;
import hermeslib.api.RankingDataProvider;
import hermeslib.HermesLibMod;
import hermeslib.rules.HermesRules;
import hermeslib.storage.DatabaseManager;
import hermeslib.storage.DatabaseManager.StatRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
public final class HermesStatsManager implements RankingDataProvider
{
    /** 定期落库间隔：5 分钟（6000 tick）。 */
    private static final int FLUSH_INTERVAL_TICKS = 20 * 60 * 5;

    /** 单 tick 超过该距离视为传送，不计入移动统计。 */
    private static final double MAX_PER_TICK_DISTANCE = 50.0D;

    /** 附魔归属的时间窗口：10 秒。 */
    private static final long ENCHANT_ATTRIBUTION_WINDOW = 10_000L;

    /** 数据库文件名（位于世界目录下）。 */
    public static final String DATABASE_FILE = "hermes-stats.db";

    /** 旧版数据库文件名，启动时若只存在它会自动迁移。 */
    public static final String LEGACY_DATABASE_FILE = "ranking-stats.db";

    private static volatile HermesStatsManager instance;

    private final ConcurrentHashMap<StatKey, PlayerStats> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPositions = new ConcurrentHashMap<>();

    /** 原版统计导入专用线程：文件 IO 与 JSON 解析都放在这里，绝不占用游戏主线程。 */
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "Hermes-Import");
        thread.setDaemon(true);
        return thread;
    });

    /** 附魔归属记录：最近一次使用附魔台/铁砧的玩家（有效 UUID + 数据域 + 时间戳）。 */
    private record EnchantUser(UUID uuid, DataDomain domain, long timestamp)
    {
    }

    private volatile EnchantUser lastEnchantUser;

    /** 附魔行为去重：同一 tick 内对同一件物品的连续 enchant() 只计 1 次（主线程访问）。 */
    private int lastEnchantCountedTick = -1;
    private ItemStack lastEnchantCountedStack;

    private volatile DatabaseManager database;
    private volatile MinecraftServer server;
    private int tickCounter;

    // ==================== 生命周期 ====================

    /** 安装全局实例（Mixin 通过静态方法访问）。 */
    public static void install(HermesStatsManager manager)
    {
        instance = manager;
    }

    /** 卸载全局实例。 */
    public static void uninstall()
    {
        instance = null;
    }

    /** 获取全局实例；服务器尚未启动时返回 null（所有调用方都做了空安全处理）。 */
    public static HermesStatsManager get()
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
        this.server = server;
        // 数据库放在世界目录下，随世界一起保存/迁移
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        Path dbFile = worldPath.resolve(DATABASE_FILE);
        migrateLegacyDatabase(worldPath, dbFile);
        DatabaseManager manager = new DatabaseManager(dbFile);
        manager.init();
        this.database = manager;
        loadFromDatabase();
    }

    /**
     * 旧版本数据库文件迁移。
     *
     * <p>早期版本把库命名为 {@code ranking-stats.db}，现在统一为 {@code hermes-stats.db}。
     * 若只存在旧文件，会先把旧库打开一次（触发 WAL checkpoint，把 WAL 合并回主库），
     * 再改名，避免丢数据。</p>
     */
    private void migrateLegacyDatabase(Path worldPath, Path target)
    {
        Path legacy = worldPath.resolve(LEGACY_DATABASE_FILE);
        if (!Files.isRegularFile(legacy)) return;
        if (Files.exists(target))
        {
            return;  // 新库已存在，保留旧文件不动
        }
        try
        {
            try (DatabaseManager legacyDatabase = new DatabaseManager(legacy))
            {
                legacyDatabase.init();   // 打开 + 建表
                legacyDatabase.close();  // checkpoint(TRUNCATE) + 关连接，WAL 已合并进主库
            }
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            HermesLibMod.LOG.info("[Hermes] 数据库已从 {} 迁移到 {}", LEGACY_DATABASE_FILE, DATABASE_FILE);
        }
        catch (Exception e)
        {
            HermesLibMod.LOG.error("[Hermes] 迁移旧数据库 {} 失败，将以空数据运行", LEGACY_DATABASE_FILE, e);
        }
    }

    /** 服务器关闭：落库 + 关闭连接。 */
    public void shutdown()
    {
        flushAll();
        DatabaseManager manager = this.database;
        this.database = null;
        this.server = null;
        if (manager != null)
        {
            manager.close();
        }
        importExecutor.shutdown();
        uninstall();
    }

    /**
     * 当前调用是否发生在服务端主线程上。
     *
     * <p>模组的 {@code environment} 为 {@code *}，因此 Mixin 在客户端也会生效；
     * 单人世界里客户端与集成服务端同处一个 JVM，共享同一份内存统计。
     * 凡是「客户端也会触发的原版逻辑」（例如附魔台界面的预览同样会调用
     * {@code ItemStack#enchant}），必须用这个判断挡掉，否则会与服务端重复计数。</p>
     */
    public boolean isServerThread()
    {
        MinecraftServer current = this.server;
        return current != null && current.isSameThread();
    }

    // ==================== 写入 ====================

    /**
     * 累加一个指标（对外给 Mixin 调用的主入口）。
     *
     * <p>会自动完成：总开关判断 → 指标开关判断 → 游戏模式过滤 → 假人识别 →
     * 数据域分流 → UUID 归一。</p>
     */
    public void add(Player player, Metric metric, long amount)
    {
        if (player == null || amount == 0L) return;
        if (!isMetricEnabled(metric)) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;  // 只统计服务端玩家
        if (!isGameModeTracked(serverPlayer)) return;                // 游戏模式不在统计范围

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
        if (HermesLibMod.DEBUG)
        {
            HermesLibMod.LOG.info("[Hermes][debug] 采集 {} +{} -> uuid={} domain={}",
                    metric.dbName(), amount, uuid, domain);
        }
    }

    /** 记录某个玩家刚刚使用了附魔台/铁砧，供后续附魔事件归属。 */
    public void recordEnchantUser(ServerPlayer player)
    {
        if (player == null) return;
        if (!isGameModeTracked(player)) return;  // 使用者模式不在统计范围，不产生归属
        lastEnchantUser = new EnchantUser(
                FakePlayerDetector.effectiveUuid(player),
                FakePlayerDetector.domainOf(player),
                System.currentTimeMillis());
    }

    /**
     * 记一次附魔行为：归属到 10 秒内最近使用过附魔台/铁砧的玩家（按其有效 UUID 与数据域写入）。
     *
     * <p><b>口径：按「行为次数」而非「附魔条数」计数。</b>注入点 {@code ItemStack#enchant}
     * 每施加一条附魔就触发一次，而附魔台/铁砧的一次操作可能附加多条附魔；
     * 这里按「同一 tick 内对同一件物品」去重，一次操作只计 1。
     * 不同物品、不同 tick 的附魔互不影响，正常分开计数。</p>
     */
    public void addEnchantment(ItemStack stack)
    {
        if (!isMetricEnabled(Metric.ENCHANTMENTS)) return;
        EnchantUser user = lastEnchantUser;
        if (user == null) return;
        if (System.currentTimeMillis() - user.timestamp() > ENCHANT_ATTRIBUTION_WINDOW) return;
        if (tickCounter == lastEnchantCountedTick && stack == lastEnchantCountedStack) return;
        lastEnchantCountedTick = tickCounter;
        lastEnchantCountedStack = stack;
        addRaw(user.uuid(), user.domain(), Metric.ENCHANTMENTS, 1L);
    }

    /** 指标是否处于开启状态（总开关 + 单项开关）。 */
    public static boolean isMetricEnabled(Metric metric)
    {
        if (!HermesRules.rankingEnabled) return false;
        switch (metric)
        {
            case PLAYTIME_TICKS:
            case PLAYTIME_SECONDS:
                return HermesRules.rankingTrackPlayTime;
            case BLOCKS_MINED:
                return HermesRules.rankingTrackBlocksMined;
            case BLOCKS_PLACED:
                return HermesRules.rankingTrackBlocksPlaced;
            case MOB_KILLS:
                return HermesRules.rankingTrackMobKills;
            case PLAYER_KILLS:
                return HermesRules.rankingTrackPlayerKills;
            case DEATHS:
                return HermesRules.rankingTrackDeaths;
            case VILLAGER_TRADES:
                return HermesRules.rankingTrackVillagerTrades;
            case FISH_CAUGHT:
                return HermesRules.rankingTrackFishCaught;
            case DISTANCE_ELYTRA:
                return HermesRules.rankingTrackElytraDistance;
            case DAMAGE_DEALT:
                return HermesRules.rankingTrackDamageDealt;
            case DAMAGE_TAKEN:
                return HermesRules.rankingTrackDamageTaken;
            case DISTANCE_WALK:
            case DISTANCE_SPRINT:
            case DISTANCE_SWIM:
            case DISTANCE_RIDE:
            case DISTANCE_TOTAL:
                return HermesRules.rankingTrackMovement;
            case ITEMS_CRAFTED:
                return HermesRules.rankingTrackItemsCrafted;
            case ITEMS_PICKED_UP:
                return HermesRules.rankingTrackItemsPickedUp;
            case ITEMS_DROPPED:
                return HermesRules.rankingTrackItemsDropped;
            case ITEMS_USED:
                return HermesRules.rankingTrackItemsUsed;
            case ITEMS_DAMAGED:
            case ITEMS_BROKEN:
                return HermesRules.rankingTrackItemsBroken;
            case ANIMALS_BRED:
                return HermesRules.rankingTrackAnimalsBred;
            case ANIMALS_TAMED:
                return HermesRules.rankingTrackAnimalsTamed;
            case ENCHANTMENTS:
                return HermesRules.rankingTrackEnchantments;
            case JUMPS:
                return HermesRules.rankingTrackJumps;
            default:
                return true;
        }
    }

    /**
     * 该玩家当前的游戏模式是否在统计范围内（真人与假人一视同仁）。
     *
     * <p>由规则 {@code rankingTrackSurvival} / {@code rankingTrackAdventure} /
     * {@code rankingTrackSpectator} / {@code rankingTrackCreative} 逐模式控制，
     * 默认全部统计。规则改动即时生效，无需重启。</p>
     */
    public static boolean isGameModeTracked(ServerPlayer player)
    {
        if (player == null) return false;
        GameType mode = player.gameMode.getGameModeForPlayer();
        return switch (mode)
        {
            case SURVIVAL -> HermesRules.rankingTrackSurvival;
            case ADVENTURE -> HermesRules.rankingTrackAdventure;
            case SPECTATOR -> HermesRules.rankingTrackSpectator;
            case CREATIVE -> HermesRules.rankingTrackCreative;
        };
    }

    // ==================== 每 tick 采集 ====================

    /**
     * 每 tick 调用一次（由 Carpet 的 tick 钩子驱动）。
     *
     * <p>负责：在线时长、移动距离、以及定期落库。</p>
     */
    public void tick(MinecraftServer server)
    {
        if (!HermesRules.rankingEnabled) return;
        tickCounter++;

        forEachOnlinePlayer(server, this::recordPlayerTick);

        if (tickCounter % FLUSH_INTERVAL_TICKS == 0)
        {
            flushAll();
            PlacedBlockTracker.prune(System.currentTimeMillis());
            InteractionTracker.prune();
        }
        else if (HermesLibMod.DEBUG && tickCounter % 100 == 0)
        {
            HermesLibMod.LOG.info("[Hermes][debug] tick={} 数据条目={}", tickCounter, data.size());
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
        if (!HermesRules.rankingTrackPlayTime) return;
        add(player, Metric.PLAYTIME_TICKS, 1L);
        if (tickCounter % 20 == 0)
        {
            add(player, Metric.PLAYTIME_SECONDS, 1L);
        }
    }

    private void recordMovement(ServerPlayer player)
    {
        if (!HermesRules.rankingTrackMovement) return;

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
        Map<UUID, String> flushedNames = new HashMap<>();
        for (Map.Entry<StatKey, PlayerStats> entry : data.entrySet())
        {
            StatKey key = entry.getKey();
            for (Map.Entry<Metric, Long> metric : entry.getValue().snapshot().entrySet())
            {
                rows.add(new StatRow(key.uuid().toString(), key.domain().id(), metric.getKey().dbName(), metric.getValue()));
            }
            String name = names.get(key.uuid());
            if (name != null)
            {
                flushedNames.put(key.uuid(), name);
            }
        }
        // last_seen 只刷新本次真正落库的玩家，不触碰历史玩家的记录
        manager.upsert(rows, flushedNames);
        if (HermesLibMod.DEBUG)
        {
            HermesLibMod.LOG.info("[Hermes][debug] 落库(周期) 行数={} 玩家数={}", rows.size(), data.size());
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
        if (HermesLibMod.DEBUG)
        {
            HermesLibMod.LOG.info("[Hermes][debug] 落库(玩家退出) uuid={} 行数={}", uuid, rows.size());
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

    // ==================== 原版统计导入 ====================

    /**
     * 导入原版（Vanilla）统计数据。
     *
     * <p><b>流程</b>：</p>
     * <ol>
     *   <li>异步线程：扫描 {@code <世界>/players/stats/<UUID>.json}，解析并按
     *       {@link VanillaStatMapping} 映射成 Hermes 指标；</li>
     *   <li>主线程：把导入值<b>覆盖</b>写进内存（原版统计本身是累计值，累加会让数值翻倍）；</li>
     *   <li>SQLite 线程：按 UPSERT 覆盖落库（同样是覆盖语义，重复导入幂等）。</li>
     * </ol>
     *
     * <p>玩家名来自 {@code usercache.json} 与在线玩家列表；两者都拿不到时，
     * 该玩家仍然会被导入，只是没有名字。</p>
     *
     * <p><b>线程</b>：本方法会等待导入完成（返回真实的 {@link ImportResult}），
     * 因此在主线程调用时会有短暂阻塞；玩家文件通常只有几十个，耗时在毫秒级。</p>
     */
    @Override
    public ImportResult importVanillaStats()
    {
        MinecraftServer server = this.server;
        if (server == null)
        {
            return new ImportResult(0, 0, 0, List.of("服务器未就绪，无法导入原版统计"));
        }

        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        // 原版统计目录：MC 26.3 是 <世界>/players/stats/<UUID>.json
        // （旧版本是 <世界>/stats/，这里直接用原版的常量，版本自适应）
        Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);

        // 名字来源（按可靠性从低到高依次覆盖）：
        // 1) Hermes 内存里已知的名字（该玩家在模组装上后玩过）
        // 2) usercache.json（服务端根目录）
        // 3) 当前在线玩家
        Map<UUID, String> nameMap = new HashMap<>(names);
        Path serverRoot = worldPath.getParent();
        if (serverRoot != null)
        {
            nameMap.putAll(VanillaStatsImporter.readUserCache(serverRoot));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            String name = playerName(player);
            if (name != null)
            {
                nameMap.put(player.getUUID(), name);
            }
        }

        // 1) 异步扫描与解析
        VanillaStatsImporter.Outcome outcome;
        try
        {
            outcome = importExecutor.submit(() -> VanillaStatsImporter.scan(statsDir, nameMap)).get(120, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            HermesLibMod.LOG.error("[Hermes] 导入原版统计失败", e);
            return new ImportResult(0, 0, 0, List.of("导入任务失败：" + message));
        }

        // 2) 组装要写入的行（覆盖语义）
        List<StatRow> rows = new ArrayList<>();
        Map<UUID, String> importedNames = new HashMap<>();
        for (VanillaStatsImporter.PlayerImport imported : outcome.players())
        {
            for (Map.Entry<Metric, Long> entry : imported.values().entrySet())
            {
                rows.add(new StatRow(imported.uuid().toString(),
                        DataDomain.REAL.id(), entry.getKey().dbName(), entry.getValue()));
            }
            if (imported.name() != null)
            {
                importedNames.put(imported.uuid(), imported.name());
            }
        }

        // 3) 切回主线程刷新内存（覆盖，不是累加）
        runOnServerThread(server, () ->
        {
            for (VanillaStatsImporter.PlayerImport imported : outcome.players())
            {
                PlayerStats stats = data.computeIfAbsent(new StatKey(imported.uuid(), DataDomain.REAL),
                        key -> new PlayerStats());
                for (Map.Entry<Metric, Long> entry : imported.values().entrySet())
                {
                    stats.set(entry.getKey(), entry.getValue());
                }
                if (imported.name() != null)
                {
                    names.put(imported.uuid(), imported.name());
                    nameIndex.put(imported.name().toLowerCase(Locale.ROOT), imported.uuid());
                }
            }
        });

        // 4) 落库（UPSERT 覆盖，幂等）
        DatabaseManager manager = this.database;
        if (manager != null)
        {
            manager.upsert(rows, importedNames);
            manager.putMeta("last_vanilla_import", Long.toString(System.currentTimeMillis()));
            manager.putMeta("last_vanilla_import_players", Integer.toString(outcome.players().size()));
        }

        HermesLibMod.LOG.info("[Hermes] 原版统计导入完成：成功 {} 人，跳过 {} 个文件，写入 {} 条指标",
                outcome.players().size(), outcome.skipped(), rows.size());

        return new ImportResult(outcome.players().size(), outcome.skipped(), rows.size(), outcome.errors());
    }

    /**
     * 在服务器主线程上执行一段逻辑，并等待其完成。
     *
     * <p>若调用方本来就在主线程上，{@code executeIfPossible} 会直接内联执行；
     * 若服务器正在关闭导致任务排队不上，则退化为在当前线程执行，保证不丢数据。</p>
     */
    private static void runOnServerThread(MinecraftServer server, Runnable action)
    {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        try
        {
            server.executeIfPossible(() ->
            {
                try
                {
                    action.run();
                }
                finally
                {
                    done.set(true);
                }
            });
        }
        catch (Throwable t)
        {
            HermesLibMod.LOG.warn("[Hermes] 切回主线程失败，改为在当前线程执行导入刷新", t);
        }
        if (!done.get())
        {
            try
            {
                // 给主线程一点时间；超时则自己执行，避免导入卡死
                for (int i = 0; i < 100 && !done.get(); i++)
                {
                    Thread.sleep(50L);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            if (!done.get())
            {
                action.run();
            }
        }
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
