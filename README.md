# Themis Lib

> 纯数据记录与接口提供型 **Carpet 附属 Mod**。
> 监听游戏行为 → 按「玩家 / 假人」分域累计 → 持久化到 SQLite → 通过 Java API（可选 Scarpet 函数）暴露数据。

**本模组刻意不提供**：任何查询命令（如 `/ranking`）、排行榜分页、计分板、HUD、Tab 列表、聊天栏展示、玩家交互 UI。
排行、奖励、展示逻辑全部由下游消费者（其它 Mod / Scarpet 脚本 / 直接读 SQLite）自行实现。

### 📄 文档

| 文档 | 内容 |
|---|---|
| [功能文档](docs/功能文档.md) | 功能总览、24 条规则、29 个指标、数据域语义、存储格式、运维要点 |
| [API 文档](docs/API文档.md) | Java API / Scarpet 函数 / 直接读 SQLite 三条接入路径、接口语义与边界、稳定契约 |
| [自测说明](selftest/README.md) | 用数据包在服务端跑一遍采集链路 |

---

## 1. 目标版本

| 项 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Java | 25+（编译用 `--release 25`） |
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.17.x（注意：新版本已移除 `modImplementation`，改用 `implementation`） |
| Carpet | 26.3（`com.github.gnembon:fabric-carpet:26.3`，JitPack） |
| SQLite | `org.xerial:sqlite-jdbc:3.53.4.0` |

> 所有 Mixin 注入点均按 **MC 26.3 / Mojang 官方映射** 核对过（用 `javap` 直接读本地
> `minecraft-merged-deobf-26.3.jar` 验证），不是凭记忆写的。

---

## 2. 项目结构

```
themis-lib/
├── build.gradle                  # Loom + Carpet 依赖 + SQLite JiJ
├── gradle.properties
└── src/main/
    ├── java/themislib/
    │   ├── ThemisLib.java                 # ModInitializer：注册 Carpet 扩展
    │   ├── ThemisExtension.java           # CarpetExtension 实现（规则/生命周期/Scarpet/翻译）
    │   ├── api/
    │   │   ├── DataDomain.java            # REAL / FAKE 数据域枚举
    │   │   ├── Metric.java                # 全部指标枚举（含 DB 取值）
    │   │   ├── RankingDataProvider.java   # 对外只读接口
    │   │   └── ThemisApi.java             # 静态获取 Provider 的入口
    │   ├── rules/ThemisRules.java         # 全部 @Rule 采集开关
    │   ├── stats/
    │   │   ├── ThemisStatsManager.java    # 内存统计中心 + 周期 flush（实现 RankingDataProvider）
    │   │   ├── PlayerStats.java           # 单玩家单域的 LongAdder 累加器
    │   │   ├── StatKey.java               # (uuid, domain)
    │   │   ├── FakePlayerDetector.java    # 假人识别 + 数据域分流 + 有效 UUID
    │   │   ├── PlacedBlockTracker.java    # 「自然方块」过滤用的放置记录
    │   │   └── InteractionTracker.java     # 繁殖归属用的交互记录
    │   ├── storage/DatabaseManager.java   # SQLite：单连接 + 单线程 + WAL + UPSERT
    │   ├── collector/ThemisCollectors.java# 伤害来源 → 攻击者玩家
    │   ├── i18n/ThemisTranslations.java    # 规则翻译（Carpet 26 新规则 API 需要）
    │   ├── scarpet/ThemisScarpet.java     # 可选 Scarpet 内置函数
    │   └── mixin/                         # 12 个采集 Mixin，见下表
    └── resources/
        ├── fabric.mod.json
        ├── themis.mixins.json
        └── assets/themis/lang/{en_us,zh_cn}.json
```

---

## 3. 规则（只注册规则，不注册任何查询命令）

规则由 Carpet 的扩展设置管理器托管：命令 `/themis`，配置文件写在 **世界目录下的 `themis.conf`**。

| 规则名 | 默认 | 作用 |
|---|---|---|
| `rankingEnabled` | true | 总开关 |
| `rankingTrackPlayTime` | true | 在线时长 |
| `rankingTrackBlocksMined` | true | 挖掘量 |
| `rankingTrackBlocksPlaced` | true | 放置量 |
| `rankingTrackMobKills` | true | 生物击杀 |
| `rankingTrackPlayerKills` | true | 玩家击杀 |
| `rankingTrackDeaths` | true | 死亡数 |
| `rankingTrackVillagerTrades` | true | 村民交易次数 |
| `rankingTrackFishCaught` | true | 钓获次数 |
| `rankingTrackElytraDistance` | true | 鞘翅飞行距离 |
| `rankingTrackDamageDealt` | true | 造成伤害 |
| `rankingTrackDamageTaken` | true | 受到伤害 |
| `rankingTrackMovement` | true | 移动距离（步行/疾跑/游泳/骑乘/鞘翅） |
| `rankingTrackItemsCrafted` | true | 合成产出数量 |
| `rankingTrackItemsPickedUp` | true | 拾取物品 |
| `rankingTrackItemsDropped` | true | 丢弃物品 |
| `rankingTrackItemsUsed` | true | 使用物品次数 |
| `rankingTrackItemsBroken` | true | 耐久损耗 / 工具损坏 |
| `rankingTrackAnimalsBred` | true | 动物繁殖次数 |
| `rankingTrackAnimalsTamed` | true | 驯服次数 |
| `rankingTrackEnchantments` | true | 附魔次数 |
| `rankingTrackJumps` | true | 跳跃次数 |
| `rankingMinedNaturalOnly` | **true** | 只统计自然方块（防放置刷榜） |
| `rankingSeparateFakePlayers` | **true** | 假人数据写入独立 FAKE 域 |

> Carpet 26 的规则描述走翻译键（`themis.rule.<规则名>.desc`），缺少翻译会直接抛异常，
> 所以 `assets/themis/lang/*.json` 必须和规则类同步维护。

---

## 4. 数据模型与 SQLite

### 4.1 表结构

```sql
CREATE TABLE IF NOT EXISTS player_stats (
    uuid   TEXT    NOT NULL,
    domain TEXT    NOT NULL,              -- 'REAL' / 'FAKE'
    metric TEXT    NOT NULL,
    value  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, domain, metric)
);
CREATE INDEX IF NOT EXISTS idx_metric_domain_value
    ON player_stats(metric, domain, value DESC);

-- 附赠：玩家名索引，方便下游直接按名字排行
CREATE TABLE IF NOT EXISTS player_names (
    uuid      TEXT    NOT NULL,
    name      TEXT    NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (uuid)
);
```

长表设计的好处：加新指标只要 INSERT 一行，不用改表结构；下游可以直接写 SQL 排行：

```sql
SELECT p.name, s.value
FROM player_stats s JOIN player_names p ON p.uuid = s.uuid
WHERE s.metric = 'blocks_mined' AND s.domain = 'REAL'
ORDER BY s.value DESC LIMIT 10;
```

### 4.2 指标清单

`metric` 取值即 `Metric` 枚举的 `dbName()`，一经发布即为稳定契约：

```
playtime_ticks   playtime_seconds
blocks_mined     blocks_placed
mob_kills        player_kills    deaths
damage_dealt     damage_taken
villager_trades  fish_caught
items_crafted    items_picked_up  items_dropped
items_used       items_damaged  items_broken
animals_bred     animals_tamed
distance_walk    distance_sprint  distance_swim  distance_ride  distance_elytra  distance_total
enchantments    jumps
```

**距离单位是「厘米」（1 格 = 100）**，用整数存储避免浮点误差。

### 4.3 写入流程

```
游戏线程（Mixin 回调）
   └─> LongAdder.add(...)          只改内存
        │
定时（5 分钟）/ 玩家退出 / 服务器关闭
   └─> 拍不可变快照 Map<StatKey, Map<Metric, Long>>
        └─> 提交给 Themis-SQLite 线程
              └─> 事务 + 批量 UPSERT（绝对值覆盖，幂等）
```

UPSERT 语句：

```sql
INSERT INTO player_stats(uuid, domain, metric, value) VALUES(?,?,?,?)
ON CONFLICT(uuid, domain, metric) DO UPDATE SET value=excluded.value;
```

写失败只记日志，**不抛到游戏线程**；下次 flush 全量覆盖修正。

---

## 5. 采集实现映射表（MC 26.3 真实注入点）

| 指标 | 注入点（类#方法） | Mixin 文件 |
|---|---|---|
| 挖掘量 / 自然方块过滤 | `ServerPlayerGameMode#destroyBlock(BlockPos)` | `ServerPlayerGameModeMixin` |
| 使用物品 | `ServerPlayerGameMode#useItem(ServerPlayer, Level, ItemStack, InteractionHand)` | `ServerPlayerGameModeMixin` |
| 附魔归属（谁在用附魔台/铁砧） | `ServerPlayerGameMode#useItemOn(...)` | `ServerPlayerGameModeMixin` |
| 放置量 + 记录「玩家放置的方块」 | `BlockItem#place(BlockPlaceContext)` | `BlockItemMixin` |
| 死亡数 / 击杀数 / 造成伤害（对生物） | `LivingEntity#die(DamageSource)`、`LivingEntity#actuallyHurt(ServerLevel, DamageSource, float)` | `LivingEntityMixin` |
| 受到伤害 / 造成伤害（对玩家） | `Player#actuallyHurt(...)` | `PlayerMixin` |
| 跳跃次数 / 丢弃物品 | `ServerPlayer#jumpFromGround()`、`ServerPlayer#drop(ItemStack, boolean, Prediction)` | `ServerPlayerMixin` |
| 动物繁殖 | `Animal#mobInteract(Player, InteractionHand)`、`Animal#spawnChildFromBreeding(ServerLevel, Animal)` | `AnimalMixin` |
| 驯服次数 | `TamableAnimal#tame(Player)` | `TamableAnimalMixin` |
| 拾取物品 | `ItemEntity#playerTouch(Player)` | `ItemEntityMixin` |
| 合成产出 | `ResultSlot#onTake(Player, ItemStack)` | `ResultSlotMixin` |
| 村民交易 | `MerchantResultSlot#onTake(Player, ItemStack)` | `MerchantResultSlotMixin` |
| 钓获次数 | `FishingHook#catchingFish(BlockPos)` | `FishingHookMixin` |
| 附魔次数 / 耐久损耗 / 工具损坏 | `ItemStack#enchant(Holder<Enchantment>, int)`、`ItemStack#hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<ItemStack>)` | `ItemStackMixin` |
| 在线时长 / 移动距离 / 周期落库 | 无需 Mixin：Carpet 的 `CarpetExtension#onTick` | `ThemisExtension` |

### 两个必须知道的覆写陷阱

1. **`Player` 覆写了 `actuallyHurt` 且不调用父类** → 在 `LivingEntity` 上注入抓不到玩家的伤害，
   所以额外写了 `PlayerMixin`。
2. **`ServerPlayer` 覆写了 `jumpFromGround` 和 `drop`** → 同理，必须挂在 `ServerPlayer` 上。

### 「仅统计自然方块」怎么实现

原版没有「方块是否自然生成」的标记位。可靠做法是**追踪放置行为**：

* 放置时（`BlockItem#place`）把 `(维度, x, y, z)` 记进 `PlacedBlockTracker`；
* 破坏时（`ServerPlayerGameMode#destroyBlock`）若命中记录 → 说明是「自己放的自己挖」→ 不计入自然挖掘量；
* 记录带 30 分钟过期时间与 20 万条容量上限，服务重启后清空。

副作用：`rankingMinedNaturalOnly=false` 时统计全部破坏（仍然消费记录，避免残留）。

---

## 6. 假人识别与数据域分流

```java
// 1) 类型判定（绝不靠 UUID）
player instanceof carpet.patches.EntityPlayerMPFake            // Carpet 假人
player instanceof net.fabricmc.fabric.api.entity.FakePlayer     // Fabric 通用假人（反射探测，可选硬依赖）

// 2) 数据域
rankingSeparateFakePlayers = true  →  假人写 FAKE 域，真人写 REAL 域
rankingSeparateFakePlayers = false →  全部写 REAL 域

// 3) 有效 UUID（防止覆盖）
真人 / Carpet 假人      → 自身 UUID
Fabric FakePlayer       → UUID.nameUUIDFromBytes("themis:fake:<归属者UUID>:<假人名>")
```

> Fabric 规范允许通用假人与真人**共享同一个 UUID**，所以第三步是必须的：
> 即使把假人写进 REAL 域，也不会覆盖真人的数据。
>
> Carpet 假人类型判定直接硬引用 Carpet（附属 Mod 本来就依赖 Carpet），
> Fabric 假人用反射探测，避免对 Fabric API 的硬依赖（MC 26.3 生态里 Fabric API 并非必需）。
> 如果你的服务器一定装了 Fabric API，可以把 `FakePlayerDetector` 里的反射改成直接 `instanceof`。

---

## 7. 对外 Java API

```java
import themislib.api.*;

// 拿到 Provider（服务器加载完成后可用）
RankingDataProvider stats = ThemisApi.provider();
if (stats != null) {
    long mined = stats.getValue(uuid, DataDomain.REAL, Metric.BLOCKS_MINED);
    long fake  = stats.getValue(uuid, DataDomain.FAKE, Metric.BLOCKS_MINED);
    Map<Metric, Long> all = stats.getAll(uuid, DataDomain.REAL);
    List<RankedEntry> top = stats.top(Metric.BLOCKS_MINED, DataDomain.REAL, 10);
    Optional<String> name = stats.getName(uuid);
    Optional<UUID> byName = stats.findByName("Steve");
    DataDomain dom = stats.domainOf(player);
}
```

`RankingDataProvider` 提供：

* `getValue(uuid, domain, metric)` / `getValue(uuid, domain, "blocks_mined")`
* `getAll(uuid, domain)` / `getTotal(uuid, domain)`
* `top(metric, domain, limit)`（供下游自行展示，模组自身不展示）
* `getName(uuid)` / `findByName(name)` / `knownPlayers()`
* `domainOf(player)` / `effectiveUuid(player)`

获取方式：`ThemisApi.provider()`（静态）。服务端在 `onServerLoaded` 时安装 Provider，
`onServerClosed` 时置空。

---

## 8. 可选 Scarpet 函数

```
ranking_data('Steve', 'blocks_mined')         -- REAL 域
ranking_data('Steve', 'blocks_mined', 'FAKE')  -- 指定域
ranking_total('Steve')                        -- REAL 域全部指标之和
```

注册方式：`CarpetExtension#scarpetApi(CarpetExpression)` → `Expression#addImpureFunction`
（用 impure，因为结果会随时间变化，不能被 Scarpet 缓存）。

---

## 9. 注意事项

1. **线程隔离**：游戏主线程只改内存、只拍快照；所有 JDBC 都在 `Themis-SQLite` 单线程上执行。
   主线程永远不会卡在磁盘 IO 上。
2. **写入幂等**：内存存绝对值，UPSERT 覆盖，重复 flush 无副作用。
3. **flush 频率**：默认 5 分钟一次（6000 tick），玩家退出与服务器关闭时各补一次。
   想要更实时可以调小 `FLUSH_INTERVAL_TICKS`。
4. **加载失败不致命**：数据库打不开或读失败只记日志，模组以空数据继续运行。
5. **规则关闭即零开销**：`isMetricEnabled(metric)` 在每次累加前检查总开关与单项开关，
   关掉的维度直接 return，不做任何计数。
6. **SQLite 体积**：`sqlite-jdbc` 含各平台原生库，约 12 MB，JiJ 后随模组分发。
   若想产出单个 jar，可改用 shadow + relocate（见 `build.gradle` 里的注释方案）。
7. **精度取舍**：
   * 距离用整数厘米；单 tick 超过 50 格视为传送并忽略；
   * 伤害取 `actuallyHurt` 的结算值（护甲/抗性结算之后），不区分吸收伤害；
   * 附魔归属靠「10 秒内最近使用过附魔台/铁砧的玩家」，铁砧附魔若不经过
     `ItemStack#enchant` 可能漏计；
   * 繁殖归属靠 `mobInteract` 交互记录（60 秒窗口），兜底取 8 格内最近玩家；
   * 放置记录重启后清空，「重启前放置、重启后挖掉」会被算作自然方块。
8. **数据域切换**：改 `rankingSeparateFakePlayers` 后，同一 UUID 的数据会在两个域之间
   "分家"（历史数据留在原域）。这是有意设计：切换开关本身也是一种可审计的语义变更。

---

## 10. 构建

```bash
cd themis-lib
./gradlew build          # 产物在 build/libs/themis-lib-<version>.jar
./gradlew runServer      # 开发环境（需要 Carpet 同版本）
```

依赖说明：

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"

    // Carpet 附属模组（Loom 1.17 已移除 modImplementation，用 implementation）
    implementation "com.github.gnembon:fabric-carpet:${project.carpet_version}"

    // SQLite：普通依赖 + JiJ 打包
    implementation "org.xerial:sqlite-jdbc:${project.sqlite_version}"
    include "org.xerial:sqlite-jdbc:${project.sqlite_version}"
}
```

> **MC 26.3 在 Fabric 上直接使用 Mojang 官方映射运行**（没有 intermediary 步骤，
> Loom 1.17 对该版本也不生成 `remapJar` 任务），因此本工程用官方映射编写，
> 产物里的类名/方法名就是运行时名字，不需要额外重映射。

---

## 11. 调试开关

启动参数加 `-Dthemis.debug=true` 会打开采集/落库的关键节点日志：

* 启动时预热全部 12 个 Mixin 目标类 —— 把「注入点写错」这种问题提前暴露在启动日志里，
  而不是等到游戏里真的发生那个行为才崩服；
* 每 100 tick 打印一次内存条目数；
* 每次累加与每次落库打印一行（指标、增量、UUID、数据域）。

正式环境不加这个参数就完全静默。

---

## 12. 已验证 / 未验证

| 项目 | 状态 | 依据 |
|---|---|---|
| 编译打包 | ✅ | `gradlew build` 通过，产出 12 MB jar（含 JiJ 的 SQLite 驱动） |
| 在 MC 26.3 + Carpet 26.3 上加载 | ✅ | 开发服务端启动成功，Carpet 扩展注册成功，规则解析无异常 |
| SQLite 建表 / WAL | ✅ | 运行目录生成 `ranking-stats.db` + `-wal` + `-shm`；关闭时 WAL 被 checkpoint 回主库 |
| 存储链路（写入→关闭→重开→读回→幂等） | ✅ | 独立冒烟测试 `SMOKE TEST PASSED`：4 行写入、REAL/FAKE 双域、同值重写仍 4 行 |
| 12 个 Mixin 注入点 | ✅ | 启动时预热全部目标类，日志「Mixin 目标类预热完成（12 个），无注入错误」 |
| 每 tick 采集钩子 | ✅ | 调试日志显示 tick 计数稳定按 20/s 递增 |
| 优雅关闭时落库 | ✅ | 关闭服务端后 `ranking-stats.db` 被 checkpoint（文件大小变化） |
| 游戏内实际采集数值 | ⚠️ 未在本机验证 | 本机没有真实客户端；Carpet 假人在**纯离线模式**下因档案解析异步失败而始终不生成（`STEVE_ENTITY_MISSING`），因此没能驱动出真实玩家行为。上线前请用 `selftest/` 里的数据包在正式服跑一遍 |

---

## 13. 已知环境坑（都已在代码里规避或注明）

1. **MC 26.3 的「空服暂停」**：`pause-when-empty-seconds=0` 的含义是「**立即**暂停」，
   不是「永不暂停」。测试时要用 `86400` 之类的较大值，否则服务端不 tick，
   `onTick` 不触发，周期落库也不会执行。
2. ** Carpet 假人是异步创建的**：`/player <name> spawn` 立即返回成功，
   但真正建出来要等玩家档案解析完成；离线模式下这一步可能永远不返回。
   用 `execute if entity <name> run say ...` 确认假人是否真的存在。
3. **Carpet 假人默认创造模式**：无敌，`/damage` 不会结算伤害。
   要测伤害得 `carpet player <name> spawn in survival`。
4. **MC 26.3 的 `GameProfile#getName()` 已改名**为 `name()`（authlib 10）。
5. **Carpet 26 的规则描述走翻译键**，缺少 `themis.rule.<规则名>.desc` 会在解析规则时直接抛异常。
6. **Loom 1.17 移除了 `modImplementation`**，模组依赖改用 `implementation`。
7. **MC 26.3 的 RCON 协议被重写**（`PktUtils`），旧版 Quake 小端协议的 RCON 客户端不再通用。

