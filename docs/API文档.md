# Hermes Lib · API 文档

> 版本 1.0.0 ｜ 模组 ID：`hermes` ｜ 目标环境 Minecraft 26.3 + Carpet 26.3
> 本文档面向**下游集成方**（其它模组、Scarpet 脚本、外部工具）。
> Hermes Lib 本身不提供任何查询命令或展示界面，所有读取都通过本文档描述的接口完成。

---

## 0. 接入方式总览

Hermes Lib 提供 **三条**互相独立的读取路径，按你的场景选一条即可：

| 路径 | 适合 | 稳定性 |
|---|---|---|
| **A. Java API** | 其它 Fabric/Carpet 模组在游戏内读取 | 稳定，需编译期依赖 |
| **B. Scarpet 函数** | 服务端脚本读取并自行展示 | 稳定，纯文本调用 |
| **C. 直接读 SQLite** | 外部工具、网站、离线分析 | 稳定，表结构是契约 |

---

## A. Java API

### A.1 引入依赖

在下游模组的 `build.gradle` 中：

```groovy
repositories {
    maven { url = file('path/to/hermes-lib/publish') }   // Hermes Lib 的本地 Maven 仓库
}

dependencies {
    implementation "com.hermeslib:hermes-lib:1.0.0"      // Loom 1.17 已移除 modImplementation，用 implementation
    // 或直接用本地 jar：
    // implementation files("libs/hermes-lib-1.0.0.jar")
}
```

`fabric.mod.json` 中声明依赖：

```json
{
  "depends": {
    "hermes_lib": "*"
  }
}
```

### A.2 获取 Provider

```java
import hermeslib.api.*;

// 方式一：可能为 null，先判断
RankingDataProvider stats = HermesApi.provider();
if (stats != null) { /* 可用 */ }

// 方式二：判断是否就绪
if (HermesApi.isReady()) { /* 可用 */ }

// 方式三：未就绪直接抛异常（IllegalStateException）
RankingDataProvider stats = HermesApi.require();
```

**生命周期契约**：

| 时机 | Provider 状态 |
|---|---|
| 服务器启动完成（`onServerLoaded`） | 已安装，`provider()` 返回实例 |
| 服务器运行期间 | 始终可用 |
| 服务器关闭（`onServerClosed`） | 置空，`provider()` 返回 `null` |

> 单人游戏里服务器可能被反复加载/卸载，请**不要**把 Provider 实例缓存到静态字段里长期使用，
> 每次用的时候重新 `HermesApi.provider()` 取。

### A.3 接口总览

```java
package hermeslib.api;

public interface RankingDataProvider
{
    record RankedEntry(UUID uuid, String name, long value) { }

    long getValue(UUID uuid, DataDomain domain, Metric metric);
    long getValue(UUID uuid, DataDomain domain, String metric);
    Map<Metric, Long> getAll(UUID uuid, DataDomain domain);
    long getTotal(UUID uuid, DataDomain domain);
    List<RankedEntry> top(Metric metric, DataDomain domain, int limit);
    Optional<String> getName(UUID uuid);
    Optional<UUID> findByName(String name);
    DataDomain domainOf(Player player);
    UUID effectiveUuid(Player player);
    Collection<UUID> knownPlayers();
    ImportResult importVanillaStats();   // 导入原版统计（覆盖语义，幂等）
}
```

### A.4 方法详解

#### `getValue(UUID, DataDomain, Metric)`

读取单个指标值。

```java
long mined = stats.getValue(uuid, DataDomain.REAL, Metric.BLOCKS_MINED);
```

| 情况 | 返回 |
|---|---|
| 正常 | 累计值（`long`） |
| `uuid` / `domain` / `metric` 为 `null` | `0` |
| 该玩家该域没有这条记录 | `0` |

#### `getValue(UUID, DataDomain, String)`

按**指标名字符串**读取，适合从配置/命令参数里传值。

```java
long v = stats.getValue(uuid, DataDomain.REAL, "blocks_mined");   // 数据库取值
long w = stats.getValue(uuid, DataDomain.FAKE,  "BLOCKS_MINED");   // 枚举名同样认
```

指标名**大小写不敏感**，同时接受数据库取值（`blocks_mined`）与枚举名（`BLOCKS_MINED`）。
非法指标名返回 `0`，**不抛异常**。

#### `getAll(UUID, DataDomain)`

返回该玩家在该域下的全部指标。

```java
Map<Metric, Long> all = stats.getAll(uuid, DataDomain.REAL);
long blocks = all.getOrDefault(Metric.BLOCKS_MINED, 0L);
```

| 情况 | 返回 |
|---|---|
| 正常 | 只包含**非零**指标的不可变 Map |
| 无记录 | `Map.of()`（空） |

> 注意：`getAll` **不包含值为 0 的指标**。要判断「某指标是否被采集过」，
> 用 `getValue(...) != 0`，不要用 `getAll().containsKey(...)`。

#### `getTotal(UUID, DataDomain)`

该玩家在该域下所有指标的**总和**，可用于自定义「综合分」。

```java
long score = stats.getTotal(uuid, DataDomain.REAL);
```

> 注意：距离类指标单位是厘米，直接相加会让分数被距离主导。自定义加权分请自行挑指标。

#### `top(Metric, DataDomain, int)`

取某指标前 N 名。

```java
List<RankingDataProvider.RankedEntry> top =
        stats.top(Metric.BLOCKS_MINED, DataDomain.REAL, 10);
for (var entry : top) {
    entry.uuid();   // UUID
    entry.name();   // 最后已知名称
    entry.value();  // 数值
}
```

| 情况 | 返回 |
|---|---|
| 正常 | 按数值**降序**；数值相同时按名称（忽略大小写）升序 |
| `metric` / `domain` 为 `null` | 空列表 |
| `limit <= 0` | 空列表 |
| 玩家无记录 | 该玩家不出现 |

> `top()` 是**便捷查询**，实现完全基于内存数据；数据量大时建议直接走路径 C（SQL）。

#### `getName(UUID)` / `findByName(String)`

```java
Optional<String> name = stats.getName(uuid);
Optional<UUID> byName = stats.findByName("Steve");   // 名称查找大小写不敏感
```

`getName` 返回玩家**最后一次已知的名称**（含已离线的玩家，来自 `player_names` 表）。
`findByName` 先查在线玩家名，再查历史名称表。

#### `domainOf(Player)` / `effectiveUuid(Player)`

```java
DataDomain domain = stats.domainOf(player);     // REAL 还是 FAKE
UUID uuid = stats.effectiveUuid(player);         // 写库时实际使用的 UUID
```

* `domainOf` 遵循规则 `rankingSeparateFakePlayers`：规则为 `true` 时假人返回 `FAKE`，
  为 `false` 时假人也返回 `REAL`。
* `effectiveUuid` 对 Fabric 通用假人会返回**派生伪 UUID**（因为它可能与真人共享 UUID），
  真人与 Carpet 假人返回自身 UUID。**要查某玩家数据，请一律用 `effectiveUuid` 得到的 UUID。**

#### `knownPlayers()`

返回内存中出现过的所有玩家 UUID（去重，含假人）。

```java
int tracked = stats.knownPlayers().size();
```

#### `importVanillaStats()` —— 导入原版统计（重点新增）

把 Minecraft 原版自己记录的统计导入 Hermes，让「装模组之前」的历史数据也能进入排行榜。

```java
ImportResult result = stats.importVanillaStats();
System.out.println("成功 " + result.playersProcessed()
        + " 人，跳过 " + result.playersSkipped()
        + " 个文件，写入 " + result.metricsImported() + " 条指标");
result.errors().forEach(System.out::println);
```

```java
public record ImportResult(
    int playersProcessed,   // 成功处理的玩家文件数
    int playersSkipped,     // 跳过（解析失败/版本不兼容/无可识别统计项）的文件数
    int metricsImported,    // 写入的指标总条数
    List<String> errors     // 错误摘要
) {}
```

| 项 | 行为 |
|---|---|
| 数据来源 | `<世界>/players/stats/<UUID>.json`（每个进过世界的玩家一个文件，含已离线玩家） |
| 名字来源 | Hermes 已知名单 → `usercache.json` → 在线玩家列表；都拿不到则只导数据不写名字 |
| 写入语义 | **覆盖**（原版统计本身是累计值，累加会让数值翻倍）；重复导入幂等 |
| 数据域 | 一律 `REAL` |
| 线程 | 文件 IO 与 JSON 解析在 `Hermes-Import` 线程；完成后切回主线程刷新内存并落库 |
| 返回时机 | 返回时数据**已经**可以在 API 中读到 |
| 失败 | 解析失败/版本不兼容的文件计入 `playersSkipped` 并写进 `errors`，不影响其它玩家 |
| 未识别项 | 原版新增的统计项一律跳过，不报错（向前兼容） |

> **注意**：本方法会等待导入完成，因此**在主线程调用时会有短暂阻塞**（玩家文件通常只有几十个，
> 耗时在毫秒级）。如果下游是「玩家执行一次命令就导入」这类场景，建议放到自己的异步线程里调用。
>
> 服务端也可以用启动参数 `-Dhermes.importVanillaOnStart=true` 在启动时自动导入一次。

映射规则见 [功能文档 §5.4](功能文档.md)，由 `hermeslib.stats.VanillaStatMapping` 集中管理。
原版没有对应统计的指标（交易、钓获、繁殖、驯服、附魔、工具损坏等）不会被覆盖，
仍以 Hermes 自己采集的值为准。

### A.5 枚举

#### `DataDomain`

```java
public enum DataDomain {
    REAL("REAL"),   // 真实玩家
    FAKE("FAKE");   // 假人

    public String id();                                  // 数据库里存的字符串
    public static Optional<DataDomain> byId(String id);  // 大小写不敏感
}
```

#### `Metric`

27 个指标，完整清单见 [功能文档 §3](功能文档.md)。与 API 相关的两个成员：

```java
public enum Metric {
    // ...
    public String dbName();                                  // 数据库取值，如 "blocks_mined"
    public static Optional<Metric> byName(String name);     // 大小写不敏感，接受 dbName 或枚举名
}
```

### A.6 完整示例：另一个模组做奖励结算

```java
import net.minecraft.server.level.ServerPlayer;
import hermeslib.api.*;

public class RewardHandler
{
    public void onServerTick(ServerPlayer player)
    {
        RankingDataProvider stats = HermesApi.provider();
        if (stats == null) return;                       // 服务器还没起来

        // 1) 查这个玩家在「真人域」挖了多少方块
        UUID uuid = stats.effectiveUuid(player);
        long mined = stats.getValue(uuid, DataDomain.REAL, Metric.BLOCKS_MINED);

        // 2) 给奖励（展示逻辑由你自己决定，Hermes 不管）
        if (mined >= 1000 && !rewardedToday.contains(uuid)) {
            giveReward(player);
            rewardedToday.add(uuid);
        }
    }

    // 3) 做排行榜（自己渲染 UI 或发给客户端）
    public List<RankingDataProvider.RankedEntry> top10()
    {
        RankingDataProvider stats = HermesApi.provider();
        return stats == null ? List.of()
                : stats.top(Metric.BLOCKS_MINED, DataDomain.REAL, 10);
    }
}
```

### A.7 线程与性能

* **所有方法都在服务器主线程调用**，实现内部线程安全（读用 `ConcurrentHashMap` + `LongAdder`）。
* 不要在异步线程里调用 `getAll` / `top` 并长期持有返回的 Map —— 返回的是快照，安全，但会占内存。
* 单次 `getValue` 是 O(1)；`top` 是 O(玩家数 × 指标数)，玩家规模大时建议走 SQL。

---

## B. Scarpet 函数

通过 Carpet 的脚本 API 调用，无需写 Java。

```
ranking_data(playerName, metric)             # 默认 REAL 域
ranking_data(playerName, metric, domain)     # 指定数据域
ranking_total(playerName)                    # REAL 域全部指标之和
ranking_total(playerName, domain)            # 指定域
```

### 返回值

| 情况 | 返回 |
|---|---|
| 正常 | 数值 |
| 玩家不存在（从未在线过） | `0` |
| 指标名非法 | `0` |
| 数据尚未就绪 | `0` |

### 示例

```
// 挖掘榜第一名
say('挖掘榜首：' + ranking_data('Steve', 'blocks_mined'))
```

```carpet
-- 输出 Steve 挖了多少方块（真人域）
say('Steve 挖掘量 = ' + ranking_data('Steve', 'blocks_mined'))

-- 输出某假人的在线秒数（假人域）
say('假人在线秒数 = ' + ranking_data('bot1', 'playtime_seconds', 'FAKE'))

-- 输出真人域综合分
say('综合分 = ' + ranking_total('Steve'))
```

> 两个函数都注册为 **impure**：返回值会随时间变化，Scarpet 不会缓存它们。

---

## C. 直接读 SQLite

### C.1 数据库位置

```
<世界>/hermes-stats.db
```

运行期还会有 `hermes-stats.db-wal` / `hermes-stats.db-shm`（WAL 模式）。
**读取时请用支持 WAL 的客户端**（Python 的 `sqlite3`、Java 的 `sqlite-jdbc`、DBeaver 等都支持），
不要用会锁库的老工具。

### C.2 表结构（契约，勿改）

```sql
-- 主表：长表，一行 = 一个（玩家, 数据域, 指标）
CREATE TABLE player_stats (
    uuid   TEXT    NOT NULL,
    domain TEXT    NOT NULL,     -- 'REAL' / 'FAKE'
    metric TEXT    NOT NULL,     -- 见功能文档 §3
    value  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, domain, metric)
);

-- 索引已针对「按指标取前 N 名」优化
CREATE INDEX idx_metric_domain_value ON player_stats(metric, domain, value DESC);

-- 玩家名索引
CREATE TABLE player_names (
    uuid      TEXT    NOT NULL,
    name      TEXT    NOT NULL,
    last_seen INTEGER NOT NULL,   -- 毫秒时间戳，最近一次被落库时的时间
    PRIMARY KEY (uuid)
);

-- 元信息（导入时间等）
CREATE TABLE hermes_meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
```

### C.3 常用查询

```sql
-- 1) 挖掘榜前 10（真人域）
SELECT p.name, s.value
FROM player_stats s
JOIN player_names p ON p.uuid = s.uuid
WHERE s.metric = 'blocks_mined' AND s.domain = 'REAL'
ORDER BY s.value DESC
LIMIT 10;

-- 2) 假人与真人分域统计
SELECT domain, COUNT(DISTINCT uuid) AS players, COUNT(*) AS rows
FROM player_stats GROUP BY domain;

-- 3) 某个玩家的全部指标
SELECT metric, value FROM player_stats
WHERE uuid = '玩家UUID' AND domain = 'REAL';

-- 4) 找出「在线时长」与「挖掘量」都进了前 10 的玩家
WITH t AS (
  SELECT uuid, value FROM player_stats WHERE metric='playtime_ticks' AND domain='REAL'
),
m AS (
  SELECT uuid, value FROM player_stats WHERE metric='blocks_mined' AND domain='REAL'
)
SELECT p.name, t.value AS ticks, m.value AS mined
FROM t JOIN m ON m.uuid = t.uuid
JOIN player_names p ON p.uuid = t.uuid
WHERE t.value >= (SELECT value FROM t ORDER BY value DESC LIMIT 10)
  AND m.value >= (SELECT value FROM m ORDER BY value DESC LIMIT 10);

-- 5) 在线时长（秒）换算
SELECT uuid, value / 20.0 AS seconds FROM player_stats
WHERE metric = 'playtime_ticks' AND domain = 'REAL';
```

### C.4 单位与口径（SQL 侧同样适用）

| 指标 | 单位 | 注意 |
|---|---|---|
| `distance_*` | 厘米 | 1 格 = 100 |
| `damage_*` | 点 | 护甲/抗性结算后的值，四舍五入 |
| `playtime_ticks` | tick | 20 tick = 1 秒 |
| 其它 | 计数 | — |

**统计范围（游戏模式过滤）**：哪些玩家会产生数据由 4 条规则逐模式控制——
`rankingTrackSurvival` / `rankingTrackAdventure` / `rankingTrackSpectator` / `rankingTrackCreative`
（默认全开，真人与假人一视同仁，逐事件判定、改动即时生效）。关闭某条后，
处于该模式的玩家对所有指标停止累计，其**历史数据保留**但不再增长。

### C.5 读写边界

* **读**：完全自由，WAL 模式支持并发读。
* **写**：请**不要**直接改 `player_stats`。Hermes Lib 在内存里保存的是绝对累计值，
  下一次落库会用 UPSERT **覆盖**你的修改（运行期间被删除的非零行也会被重新写回）。
  要清数据请先优雅关闭服务端，再删除对应的行——重启后不会复活。
* **备份**：直接复制 `hermes-stats.db` 即可；若想保证一致，先优雅关闭服务端
  （关闭时会 checkpoint 并截断 WAL）。

---

## D. 稳定契约与版本策略

以下内容**一旦发布即为稳定契约**，可以放心写进你的代码：

| 契约 | 说明 |
|---|---|
| `metric` 取值 | 只增不改；新增指标不会破坏旧查询 |
| `domain` 取值 | 固定 `REAL` / `FAKE` |
| `Metric.dbName()` | 与数据库列值一一对应 |
| `DataDomain.id()` | 与数据库列值一一对应 |
| 表名与列名 | `player_stats` / `player_names` |
| Java 包名与接口名 | `hermeslib.api.*` |

**可能变化的部分**（不保证）：Java 内部实现细节、新增的枚举常量、
新增的方法（新增不会破坏兼容性，但请不要依赖枚举的 `ordinal()` 顺序）。

> 读数据库时遇到**未知 metric** 请忽略该行（而不是报错）——
> 新版 Hermes 增加了指标时，旧版读取方应当优雅跳过。

---

## E. 故障排查

| 现象 | 原因与处理 |
|---|---|
| `HermesApi.provider()` 返回 `null` | 服务器尚未加载完成或已关闭；用 `isReady()` 判断 |
| 查某玩家全是 0 | 该玩家从未产生过该指标的记录；确认 UUID 是否用了 `effectiveUuid(player)` 的结果 |
| 假人数据混进了真人榜 | 检查 `rankingSeparateFakePlayers` 规则；即便关闭，假人 UUID 也是独立的，不会覆盖真人 |
| SQL 读到的数据比游戏里看到的少 | 内存数据最长 5 分钟才落库一次；玩家退出或关服会立即落库 |
| 数据库打不开 | 服务端日志会有 `[Hermes] 无法打开 SQLite 数据库`；此时模组以空数据继续运行，不会崩服 |
| 想看采集细节 | 启动加 `-Dhermes.debug=true`，会输出每次采集与落库 |
