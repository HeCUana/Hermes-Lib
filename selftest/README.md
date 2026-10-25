# Hermes Lib 自测数据包

把 `datapack/hermestest` 放进世界目录的 `datapacks/` 下，重启服务端即可自动执行：

```
世界目录/datapacks/hermestest/
├── pack.mcmeta
└── data/
    ├── minecraft/tags/function/load.json
    └── hermestest/function/{load,spawn,move,damage,kill}.mcfunction
```

执行内容（服务器加载后自动触发）：

| 时刻 | 动作 | 预期采集 |
|---|---|---|
| 20t | `carpet player steve spawn in survival` | 生成一个 Carpet 假人（应落在 **FAKE** 域） |
| 60t | `tp steve ~4 ~ ~` ×4 | `distance_walk` / `distance_total` 增加 |
| 100t | `damage steve 5` | `damage_taken` 增加 |
| 140t | `carpet player steve kill` | `deaths` 增加 |

每个函数都带 `say`，方便在服务端控制台/日志里确认执行情况。

## 注意事项

* **假人必须在生存模式生成**：Carpet 假人默认创造模式，无敌，`damage` 不会结算。
* **离线模式服务器上 Carpet 假人可能生成不出来**：
  Carpet 的假人创建是异步的（要先解析玩家档案），在纯离线模式下档案解析可能一直不返回，
  于是「命令返回成功但假人从未出现」。
  线上正式服一般是正版验证或已有档案缓存，不受影响；
  若要排查，用 `execute if entity steve run say STEVE_EXISTS` 确认假人是否真的存在。
* 生成假人后不要立刻 kill，否则采集窗口太短、看不出数据。
* 落库有 5 分钟周期（或玩家退出 / 服务器关闭时触发），
  想立刻看到数据可以关掉服务端——优雅关闭会强制 flush 并 checkpoint WAL。
