# game-patch（Java 主机测试集与 v1 历史参考）

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [English README](../README.en.md) · [系统架构](../docs/ARCHITECTURE.md) · [构建测试指南](../docs/BUILD-AND-TEST.md)

> **免责声明**：本目录仅供个人学习与自动化回归测试参考，使用本项目必须搭配已合法购买的正版 Steam《暗黑地牢》目录。

从 **v2.0** 起，主线已升级为 [app/](../app/README.md) 的一体化单 APK 启动器架构。本目录当前主要保留两类内容：

1. **Java 主机端自动化验证集（`test/dev/torcharchive/game/`）**：
   - [DsonCodecCheck.java](test/dev/torcharchive/game/DsonCodecCheck.java)：校验真实二进制 `persist.game.json` 的字节级无损往返、Steam→本地 Mod 映射与重排逻辑；
   - [SnapshotStoreCheck.java](test/dev/torcharchive/game/SnapshotStoreCheck.java)：校验连续提交自动留档、防抖去重、多文件逐字节恢复、损坏快照拒绝与中断恢复；
   - [UnifiedEngineCheck.java](test/dev/torcharchive/game/UnifiedEngineCheck.java)：校验 `DisplayConfig` 多分辨率预设、`SaveEditor` 全维度编辑及 `ContentModGenerator` 规则 Mod 生成。
2. **v1 双包架构历史参考源码（`src/dev/torcharchive/game/`）**：保留早期跨应用 `ContentProvider` 方案的参考实现，供架构演进对照。
