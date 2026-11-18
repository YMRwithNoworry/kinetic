# TaCZ 玩家弹道命中链

> 本页记录当前依赖版本中 TaCZ 的服务器命中链及 KINETIC 的接入边界。KINETIC 模型 OBB 命中尚未实现；当前玩家子弹命中仍完全使用 TaCZ 自己的 AABB 路径。

## 当前 TaCZ 调用链

构建固定使用 Modrinth 官方发行 `maven.modrinth:timeless-and-classics-zero:1.2.0-hotfix`（版本 ID `4yAbMXbz`，Minecraft 1.20.1 Forge，2026-11-15 发布）；截至 2026-11-18，这是该项目针对本仓库 MC/loader 组合的最新发行。对应 CurseForge 文件为 `9623132`。本页的调用链已直接按该发行源码核验，不以其他 MC 版本或旧版本推断兼容性。

在该 `1.2.0-hotfix` 发行中，`EntityKineticBullet.onBulletTick()` 先将子弹线段交给方块射线检测，再根据穿透/爆炸模式调用 `EntityUtil.findEntityOnPath()` 或 `findEntitiesOnPath()`。后者按子弹扫掠区域收集候选实体，逐个调用 `EntityUtil.getHitResult()`，再按线段起点到命中点的距离排序；`TacHitResult` 将实体、命中点和 headshot 布尔值交给子弹后续伤害流程。

`EntityUtil.getHitResult()` 当前对实体调用 `HitboxHelper.getFixedBoundingBox()` 并对 AABB 做线段相交。TaCZ 玩家延迟补偿选项开启时，`HitboxHelper` 保存历史玩家位置、原版 AABB 与速度；它并不保存 KINETIC 骨骼姿态或模型区域。Headshot 使用 TaCZ 按实体类型配置的头部 AABB；未配置时使用眼高附近的固定高度区间。候选收集边界和命中实体的历史 AABB 是两处独立条件，任何未来窄相都必须同时满足，且不得为此修改 Minecraft `Entity` 的物理 bounding box。该最新版没有为模型 hitbox 新增接口，故更新依赖本身不会解锁服务端 OBB 姿态来源。

## KINETIC 边界与当前限制

服务端已有玩家模型选择、catalog 内容身份与受控模型来源。模型几何定义以 render-target 数据块存储；但服务端当前没有将其转换为 hitbox 的运行时 owner，也没有与客户端 `ModelRenderTargetLoader` 对应的玩家骨骼姿态求值 owner，更不求值 KINETIC animation、controller 或 bones。当前动画同步不是逐骨骼、逐时刻的权威姿态快照。客户端绘制矩阵因此不能成为服务端 hitbox 输入。

KINETIC 当前的 Mixin 配置和可选依赖门面没有 TaCZ 服务器命中接入，既不生成 OBB，也不改变 TaCZ 候选收集、历史 AABB、命中点或 headshot。完整实现前，所有玩家子弹命中保持 TaCZ 原有行为；服务端姿态来源与阻塞原因见[支持缺口](../../status/known-issues/animation.md#tacz-player-projectile-hit-detection)。

## 版本与验证边界

`EntityKineticBullet`、`EntityUtil`、`HitboxHelper` 与 `TacHitResult` 是 TaCZ 内部实现，不是稳定 API。任何后续接入必须针对构建实际固定的 1.2.0-hotfix 构件核对候选收集、回溯样本、命中点和 headshot 全链路，并在可选依赖隔离下验证缺失/不匹配回退；只通过另一个 TaCZ 版本的编译或离线检查不能证明兼容。
