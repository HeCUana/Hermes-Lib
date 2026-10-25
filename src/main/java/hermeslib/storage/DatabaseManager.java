package hermeslib.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 持久化管理。
 *
 * <p><b>线程模型</b>：单连接 + 单线程 {@link ExecutorService}。
 * 所有 JDBC 操作都在 {@code Hermes-SQLite} 线程上执行，游戏主线程只负责
 * {@link #upsert(List, Map)} 提交快照，永远不直接触碰 JDBC。</p>
 *
 * <p><b>写入语义</b>：内存里保存的是「绝对累计值」，落库使用 UPSERT 覆盖，
 * 因此重复 flush 是幂等的；写入失败只记日志，下次 flush 会全量覆盖修正。</p>
 */
public final class DatabaseManager implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger("Hermes Lib/SQLite");

    /** 一行统计数据（已转成数据库可直接写入的字符串形态）。 */
    public record StatRow(String uuid, String domain, String metric, long value)
    {
    }

    private static final String CREATE_STATS_TABLE = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid   TEXT    NOT NULL,
                domain TEXT    NOT NULL,
                metric TEXT    NOT NULL,
                value  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, domain, metric)
            )
            """;

    private static final String CREATE_STATS_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_metric_domain_value
                ON player_stats(metric, domain, value DESC)
            """;

    /** 玩家名索引，方便下游直接按名字排行（模组自身不提供查询）。 */
    private static final String CREATE_NAMES_TABLE = """
            CREATE TABLE IF NOT EXISTS player_names (
                uuid      TEXT    NOT NULL,
                name      TEXT    NOT NULL,
                last_seen INTEGER NOT NULL,
                PRIMARY KEY (uuid)
            )
            """;

    /** 元信息表（键值对），存放导入时间、模式版本之类的附加信息。 */
    private static final String CREATE_META_TABLE = """
            CREATE TABLE IF NOT EXISTS hermes_meta (
                key   TEXT PRIMARY KEY,
                value TEXT
            )
            """;

    private static final String UPSERT_STATS = """
            INSERT INTO player_stats(uuid, domain, metric, value) VALUES(?,?,?,?)
            ON CONFLICT(uuid, domain, metric) DO UPDATE SET value=excluded.value
            """;

    private static final String UPSERT_NAME = """
            INSERT INTO player_names(uuid, name, last_seen) VALUES(?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen
            """;

    private final Path dbFile;
    private final ExecutorService executor;

    /** 仅在 DB 线程上访问。 */
    private Connection connection;
    private volatile boolean closed = false;

    public DatabaseManager(Path dbFile)
    {
        this.dbFile = dbFile;
        this.executor = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "Hermes-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path databaseFile()
    {
        return dbFile;
    }

    /**
     * 打开连接并建表。失败只记日志，模组继续以空数据运行。
     *
     * <p>连接在 DB 线程上创建，之后也只在该线程上使用。</p>
     */
    public void init()
    {
        try
        {
            executor.submit(this::openAndPrepare).get(60, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.error("[Hermes] SQLite 初始化失败，统计数据将只保留在内存中", e);
        }
    }

    private void openAndPrepare()
    {
        try
        {
            Path parent = dbFile.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            // 显式加载驱动，保证 JiJ / shadow 场景下也能拿到
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA journal_mode=WAL;");
                statement.execute("PRAGMA synchronous=NORMAL;");
                statement.execute("PRAGMA foreign_keys=ON;");
                statement.execute("PRAGMA busy_timeout=5000;");
                statement.execute(CREATE_STATS_TABLE);
                statement.execute(CREATE_STATS_INDEX);
                statement.execute(CREATE_NAMES_TABLE);
                statement.execute(CREATE_META_TABLE);
            }
            LOG.info("[Hermes] SQLite 已就绪：{}", dbFile.toAbsolutePath());
        }
        catch (Exception e)
        {
            LOG.error("[Hermes] 无法打开 SQLite 数据库：{}", dbFile.toAbsolutePath(), e);
        }
    }

    /** 读取全部历史统计数据；失败返回空列表。 */
    public List<StatRow> loadAll()
    {
        try
        {
            return executor.submit(this::doLoadAll).get(120, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.error("[Hermes] 读取历史统计失败，将以空数据运行", e);
            return Collections.emptyList();
        }
    }

    private List<StatRow> doLoadAll()
    {
        List<StatRow> rows = new ArrayList<>();
        if (connection == null) return rows;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, domain, metric, value FROM player_stats"))
        {
            while (rs.next())
            {
                rows.add(new StatRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
        }
        catch (SQLException e)
        {
            LOG.error("[Hermes] 读取 player_stats 失败", e);
        }
        return rows;
    }

    /** 读取玩家名索引。 */
    public Map<UUID, String> loadNames()    {
        try
        {
            return executor.submit(this::doLoadNames).get(120, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.error("[Hermes] 读取玩家名索引失败", e);
            return Collections.emptyMap();
        }
    }

    private Map<UUID, String> doLoadNames()
    {
        Map<UUID, String> result = new java.util.HashMap<>();
        if (connection == null) return result;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, name FROM player_names"))
        {
            while (rs.next())
            {
                try
                {
                    result.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
                catch (IllegalArgumentException ignored)
                {
                    // 脏数据，跳过
                }
            }
        }
        catch (SQLException e)
        {
            LOG.error("[Hermes] 读取 player_names 失败", e);
        }
        return result;
    }

    /**
     * 写入一条元信息（异步）。
     *
     * @return 提交完成的 future；失败只记日志
     */
    public CompletableFuture<Void> putMeta(String key, String value)
    {
        if (key == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() ->
        {
            if (connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO hermes_meta(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value"))
            {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            catch (SQLException e)
            {
                LOG.error("[Hermes] 写入 hermes_meta 失败", e);
            }
        }, executor);
    }

    /** 读取一条元信息；不存在或读取失败时返回空。 */
    public Optional<String> getMeta(String key)
    {
        if (key == null) return Optional.empty();
        try
        {
            return executor.submit(() ->
            {
                if (connection == null) return Optional.<String>empty();
                try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM hermes_meta WHERE key = ?"))
                {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery())
                    {
                        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                    }
                }
                catch (SQLException e)
                {
                    LOG.error("[Hermes] 读取 hermes_meta 失败", e);
                    return Optional.<String>empty();
                }
            }).get(30, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.error("[Hermes] 读取 hermes_meta 失败", e);
            return Optional.empty();
        }
    }

    /**
     * 批量写入（UPSERT 绝对值覆盖）。
     *
     * @return 一个 future，完成后代表已提交；失败只记日志
     */
    public CompletableFuture<Void> upsert(List<StatRow> rows, Map<UUID, String> names)
    {
        if (rows.isEmpty() && (names == null || names.isEmpty()))
        {
            return CompletableFuture.completedFuture(null);
        }
        Map<UUID, String> nameSnapshot = names == null ? Collections.emptyMap() : new java.util.HashMap<>(names);
        return CompletableFuture.runAsync(() -> doUpsert(rows, nameSnapshot), executor);
    }

    private void doUpsert(List<StatRow> rows, Map<UUID, String> names)
    {
        if (connection == null) return;
        try
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try
            {
                if (!rows.isEmpty())
                {
                    try (PreparedStatement ps = connection.prepareStatement(UPSERT_STATS))
                    {
                        int batched = 0;
                        for (StatRow row : rows)
                        {
                            ps.setString(1, row.uuid());
                            ps.setString(2, row.domain());
                            ps.setString(3, row.metric());
                            ps.setLong(4, row.value());
                            ps.addBatch();
                            if (++batched >= 512)
                            {
                                ps.executeBatch();
                                ps.clearBatch();
                                batched = 0;
                            }
                        }
                        ps.executeBatch();
                    }
                }
                if (!names.isEmpty())
                {
                    long now = System.currentTimeMillis();
                    try (PreparedStatement ps = connection.prepareStatement(UPSERT_NAME))
                    {
                        for (Map.Entry<UUID, String> entry : names.entrySet())
                        {
                            ps.setString(1, entry.getKey().toString());
                            ps.setString(2, entry.getValue());
                            ps.setLong(3, now);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                connection.commit();
            }
            catch (SQLException e)
            {
                LOG.error("[Hermes] 写入统计失败（下次 flush 会全量覆盖修正）", e);
                try
                {
                    connection.rollback();
                }
                catch (SQLException ignored)
                {
                    // 忽略
                }
            }
            finally
            {
                try
                {
                    connection.setAutoCommit(previousAutoCommit);
                }
                catch (SQLException ignored)
                {
                    // 忽略
                }
            }
        }
        catch (SQLException e)
        {
            LOG.error("[Hermes] 写入统计失败", e);
        }
    }

    @Override
    public void close()
    {
        if (closed) return;
        closed = true;
        try
        {
            executor.submit(() ->
            {
                if (connection == null) return;
                try (Statement statement = connection.createStatement())
                {
                    // 关闭前把 WAL 合并回主库，避免留下大 -wal 文件
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE);");
                }
                catch (SQLException e)
                {
                    LOG.warn("[Hermes] WAL checkpoint 失败", e);
                }
                try
                {
                    connection.close();
                }
                catch (SQLException e)
                {
                    LOG.warn("[Hermes] 关闭 SQLite 连接失败", e);
                }
            }).get(30, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.warn("[Hermes] 关闭数据库时出现异常", e);
        }
        finally
        {
            executor.shutdown();
            try
            {
                if (!executor.awaitTermination(15, TimeUnit.SECONDS))
                {
                    executor.shutdownNow();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
