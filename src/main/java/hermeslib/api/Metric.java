package hermeslib.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hermes Lib 采集的所有统计指标。
 *
 * <p>每个指标在 SQLite 中对应 {@code player_stats.metric} 列的一个取值，
 * 取值一经发布即为稳定契约（下游脚本可直接写 SQL 排行），新增指标只能追加、不能改名。</p>
 *
 * <p>长度类指标（{@code DISTANCE_*}）的单位是「厘米（centi-block）」，
 * 即 1 格 = 100，避免用浮点数存储；其余指标为计数。</p>
 */
public enum Metric
{
    // ---- 在线时长 ----
    /** 在线 tick 数（20 tick = 1 秒）。 */
    PLAYTIME_TICKS("playtime_ticks"),
    /** 在线秒数（向下取整）。 */
    PLAYTIME_SECONDS("playtime_seconds"),

    // ---- 方块 ----
    /** 挖掘的方块数量。 */
    BLOCKS_MINED("blocks_mined"),
    /** 放置的方块数量。 */
    BLOCKS_PLACED("blocks_placed"),

    // ---- 战斗 ----
    /** 击杀生物数量。 */
    MOB_KILLS("mob_kills"),
    /** 击杀玩家数量。 */
    PLAYER_KILLS("player_kills"),
    /** 死亡次数。 */
    DEATHS("deaths"),
    /** 造成伤害（取实际结算值，单位为点，向上取整）。 */
    DAMAGE_DEALT("damage_dealt"),
    /** 受到伤害（取实际结算值，单位为点，向上取整）。 */
    DAMAGE_TAKEN("damage_taken"),

    // ---- 交互 / 经济 ----
    /** 村民交易次数。 */
    VILLAGER_TRADES("villager_trades"),
    /** 钓获次数（每回收一次战利品计 1）。 */
    FISH_CAUGHT("fish_caught"),
    /** 制作（合成）产出的物品数量。 */
    ITEMS_CRAFTED("items_crafted"),
    /** 捡起的物品数量。 */
    ITEMS_PICKED_UP("items_picked_up"),
    /** 丢弃的物品数量。 */
    ITEMS_DROPPED("items_dropped"),
    /** 使用物品次数。 */
    ITEMS_USED("items_used"),
    /** 造成耐久损耗的次数。 */
    ITEMS_DAMAGED("items_damaged"),
    /** 损坏（碎裂）的工具/装备数量。 */
    ITEMS_BROKEN("items_broken"),

    // ---- 生物 ----
    /** 动物繁殖次数。 */
    ANIMALS_BRED("animals_bred"),
    /** 驯服次数。 */
    ANIMALS_TAMED("animals_tamed"),

    // ---- 移动 ----
    /** 步行距离（厘米，不含疾跑）。 */
    DISTANCE_WALK("distance_walk"),
    /** 疾跑距离（厘米）。 */
    DISTANCE_SPRINT("distance_sprint"),
    /** 游泳距离（厘米）。 */
    DISTANCE_SWIM("distance_swim"),
    /** 骑乘距离（厘米）。 */
    DISTANCE_RIDE("distance_ride"),
    /** 鞘翅飞行距离（厘米）。 */
    DISTANCE_ELYTRA("distance_elytra"),
    /** 水平移动总距离（厘米，等于上面五项之和）。 */
    DISTANCE_TOTAL("distance_total"),

    // ---- 其它 ----
    /** 附魔行为次数（附魔台/铁砧/命令一次操作计 1，不按附魔条数计）。 */
    ENCHANTMENTS("enchantments"),
    /** 跳跃次数。 */
    JUMPS("jumps");

    private static final Map<String, Metric> BY_NAME = new HashMap<>();

    static
    {
        for (Metric m : values())
        {
            BY_NAME.put(m.dbName, m);
            BY_NAME.put(m.name().toLowerCase(java.util.Locale.ROOT), m);
        }
    }

    private final String dbName;

    Metric(String dbName)
    {
        this.dbName = dbName;
    }

    /** 数据库中 {@code player_stats.metric} 的取值。 */
    public String dbName()
    {
        return dbName;
    }

    /** 按数据库取值或枚举名查找指标。 */
    public static Optional<Metric> byName(String name)
    {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(name.trim().toLowerCase(java.util.Locale.ROOT)));
    }
}
