package hermeslib.api;

import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hermes Lib 对外暴露的只读数据接口。
 *
 * <p>本模组自身<b>不提供</b>任何查询命令、排行榜 UI、计分板或 Tab 展示；
 * 排行、奖励、查询展示全部由下游消费者（其它 Mod、Scarpet 脚本、直接读 SQLite）实现。</p>
 *
 * <p>线程约定：所有方法都在服务器主线程调用，实现内部保证线程安全。</p>
 */
public interface RankingDataProvider
{
    /** 排行榜条目。 */
    record RankedEntry(UUID uuid, String name, long value)
    {
    }

    /**
     * 读取单个指标。
     *
     * @param uuid   玩家 UUID（假人为其「有效 UUID」，见 {@link #effectiveUuid(Player)}）
     * @param domain 数据域
     * @param metric 指标
     * @return 累计值；无记录时返回 0
     */
    long getValue(UUID uuid, DataDomain domain, Metric metric);

    /** 按指标名读取，指标名非法时返回 0。 */
    long getValue(UUID uuid, DataDomain domain, String metric);

    /** 读取某玩家在某数据域下的全部指标。 */
    Map<Metric, Long> getAll(UUID uuid, DataDomain domain);

    /** 读取某玩家在某数据域下的指标总和（可用于自定义「综合分」）。 */
    long getTotal(UUID uuid, DataDomain domain);

    /**
     * 取某指标的前 N 名（供下游消费者自行展示；本模组不负责任何展示）。
     *
     * @param limit 最多返回条数
     */
    List<RankedEntry> top(Metric metric, DataDomain domain, int limit);

    /** 玩家最后已知名称。 */
    Optional<String> getName(UUID uuid);

    /** 按名称查找 UUID（在线玩家 + 历史记录）。 */
    Optional<UUID> findByName(String name);

    /** 判断某个在线实体属于哪个数据域。 */
    DataDomain domainOf(Player player);

    /**
     * 计算玩家写入数据库时使用的「有效 UUID」。
     *
     * <p>真实玩家与 Carpet 假人使用自身 UUID；Fabric 通用假人可能与真人共享 UUID，
     * 这里会派生一个稳定且独立的伪 UUID，避免相互覆盖。</p>
     */
    UUID effectiveUuid(Player player);

    /** 所有已知玩家（内存中出现过的）。 */
    java.util.Collection<UUID> knownPlayers();
}
