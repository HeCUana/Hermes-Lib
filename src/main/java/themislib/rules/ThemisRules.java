package themislib.rules;

import carpet.api.settings.Rule;

/**
 * Themis Lib 的全部 Carpet 规则。
 *
 * <p>本模组只注册规则，不注册任何查询命令：规则由 Carpet 的扩展设置管理器
 * （命令 {@code /themis}）托管，配置文件写在世界目录下的 {@code themis.conf}。</p>
 *
 * <p>字段名即规则名，只能追加、不可改名（改名会让已有配置失效）。</p>
 */
public class ThemisRules
{
    /** 规则分类，对应翻译键 {@code themis.category.ranking}。 */
    public static final String CATEGORY = "ranking";

    // ==================== 总开关 ====================

    /** 总开关：关闭后所有采集停止，规则本身保留。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingEnabled = true;

    // ==================== 采集维度开关 ====================

    /** 采集在线时长。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackPlayTime = true;

    /** 采集挖掘量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackBlocksMined = true;

    /** 采集放置量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackBlocksPlaced = true;

    /** 采集生物击杀数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackMobKills = true;

    /** 采集玩家击杀数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackPlayerKills = true;

    /** 采集死亡数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackDeaths = true;

    /** 采集村民交易次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackVillagerTrades = true;

    /** 采集钓获次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackFishCaught = true;

    /** 采集鞘翅飞行距离。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackElytraDistance = true;

    /** 采集造成伤害。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackDamageDealt = true;

    /** 采集受到伤害。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackDamageTaken = true;

    /** 采集移动距离（步行/疾跑/游泳/骑乘/鞘翅）。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackMovement = true;

    /** 采集制作（合成）产出数量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackItemsCrafted = true;

    /** 采集拾取物品数量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackItemsPickedUp = true;

    /** 采集丢弃物品数量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackItemsDropped = true;

    /** 采集使用物品次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackItemsUsed = true;

    /** 采集工具/装备损坏数量。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackItemsBroken = true;

    /** 采集动物繁殖次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackAnimalsBred = true;

    /** 采集驯服次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackAnimalsTamed = true;

    /** 采集附魔次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackEnchantments = true;

    /** 采集跳跃次数。 */
    @Rule(categories = { CATEGORY })
    public static boolean rankingTrackJumps = true;

    // ==================== 采集策略 ====================

    /**
     * 仅统计「自然生成」的方块。
     *
     * <p>实现方式：记录玩家放置过的方块坐标，破坏时若命中记录则不计入自然挖掘量，
     * 从而杜绝「放置 → 立刻挖掉」的刷榜行为。</p>
     */
    @Rule(categories = { CATEGORY })
    public static boolean rankingMinedNaturalOnly = true;

    /**
     * 假人数据域是否与真实玩家分离。
     *
     * <p>开启（默认）：假人写入 {@link themislib.api.DataDomain#FAKE} 域。</p>
     * <p>关闭：假人写入 {@link themislib.api.DataDomain#REAL} 域，但 UUID 仍然独立，
     * 不会覆盖同名真实玩家的数据。</p>
     */
    @Rule(categories = { CATEGORY })
    public static boolean rankingSeparateFakePlayers = true;

    private ThemisRules()
    {
    }
}
