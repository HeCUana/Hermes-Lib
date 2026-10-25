# Hermes Lib

> 纯后端 **Carpet 附属 Mod**。
> 监听游戏行为 → 按「玩家 / 假人」分域累计 → 持久化到 SQLite → 通过 Java API（可选 Scarpet 函数）暴露数据。

**本模组刻意不提供**：任何游戏内前端查询手段（如 `/ranking`、排行榜、分页、计分板、HUD、Tab 列表、聊天栏展示、玩家交互 UI）。它只负责采集、存储与暴露数据，展示由下游自行实现。

## 1. 应用环境

| 项 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.17.x |

---

## 2. 构建

```bash
./gradlew build          # 产物在 build/libs/themis-lib-<version>.jar
./gradlew runServer      # 开发环境（需要 Carpet 同版本）
```

构建配置（Loom + Carpet 依赖 + SQLite JiJ）见 `build.gradle` 与 [架构与实现](doc/架构与实现.md)。

---

## 3. 协议

本项目基于 [LICENSE](LICENSE) 所载协议分发。
