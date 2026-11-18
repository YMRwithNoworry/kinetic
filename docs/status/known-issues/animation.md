# 动画已知问题

本页只记录当前实现可证实的动画缺口。正常职责和数据流见[动画架构](../../architecture/animation/README.md)。

## TaCZ player projectile hit detection

- 当前构建固定依赖 Modrinth 官方 `maven.modrinth:timeless-and-classics-zero:1.2.0-hotfix`（版本 ID `4yAbMXbz`，Minecraft 1.20.1 Forge，2026-11-15 发布；截至 2026-11-18 为该组合最新发行，对应 CurseForge 文件 `9623132`）。此发行的玩家弹道命中仍由 TaCZ `EntityUtil` / `HitboxHelper` 处理；候选实体、线段命中、历史 AABB 与 headshot 均保留 TaCZ 行为，KINETIC 尚无针对该调用链的运行时代码。
- 服务端持有经其裁决的模型选择和 model data 来源，但不求值 KINETIC 的 animation、controller 或 bones；当前同步也不包含可被服务端信任的逐骨骼姿态。因而目前没有可证明完整的服务端 OBB 姿态支持集。客户端渲染矩阵、partial-tick 骨骼状态或客户端提交的 hitbox 不能代替服务端事实。**当前所有姿态都沿用 TaCZ 原 AABB**，并未改写 Minecraft 玩家物理 AABB。
- 在服务端姿态 owner 和可验证形状建立以前，不接入 TaCZ Mixin，也不生成不完整 OBB。产品授权的精确例外、范围和失败边界见[决策](../../product-decisions/decisions/visual-gameplay-boundary.md#bctacz-player-bullet-model-hitbox)；当前调用链见[架构说明](../../architecture/integration/tacz-hit-detection.md)。缺少 TaCZ 的服务端/双客户端运行验证；当前未声称 OBB 命中可用。

## 求值与语义

- `isMoving` 当前有逻辑错误，会影响依赖该字段的 `CodedAnimationController`。
- 头部 yaw / pitch 输入已经计算，但 coded head-bone 应用仍被禁用；第一人称手臂也使用独立且未完成的动画运行时，未共享完整 controller 进度。
- 骨骼基准 snapshot 错误地以 `children_hidden` 初始化 `cubes_hidden`，两个可见性通道可能互相污染。
- Controller 的旋转混合仍保留不完整的历史行为；当前 Schema 的 `State` 已没有 `blend_via_shortest_path` 字段（field 7 空置），reader 一律按 `false` 处理，因此该行为无法由模型表达。
- 骨骼绝对轴心由 `kinetic.bone_pivot_abs` 提供；引用旧 `kinetic.bone_absolute_pivot` 名称的模型不会解析到该函数，依赖它的行为缺口仍存在。
- Sound keyframe 与 controller-state sound effect 都能进入同一 `SoundInstanceManager`：无冒号名称解析当前 render target 的模型声音，有冒号名称继续播放 Minecraft `SoundEvent`。模型声音的内容与播放主链已有局部自动化覆盖，但真实 Forge world 的 once-only 触发、音量、OpenAL adoption、设备容量和停止时序仍未验收；`kinetic.play_sound` 使用相同路由。

## 求值频率与逐帧缓存

- 动画求值频率阶梯只有离线与结构判断，没有游戏内 A/B：新旧默认在不同刷新率下的观感差异、"60 Hz 是否足够"都没有实机测量。
- 求值频率上限不减少 `ModelState::Extract`：native 骨骼姿态变换仍每个渲染帧执行一次，因此该机制省下的是 controller / Molang / 物理 / 前后处理，不是全部逐帧动画成本；这是待闭合项。
- 头部朝向刻意不纳入求值门禁：`CustomHumanoidEntity.codeAnimation` 每渲染帧重写 locator 旋转，因此高频转头不会被降频影响，但也没有独立的实机验证。
- Curios 的装备查询（`hasAnyItemEquipped*`）现在只在渲染线程上执行；`CuriosApi` 返回的
  `ItemStackHandler` 不是线程安全的，非渲染线程直接放弃判定（条件为 false）。动画求值当前走
  `update -> tickAnimation -> predicate`、与渲染同线程，所以这道门对正常路径没有影响；
  它防的是意外的跨线程调用，以及将来把求值挪到 worker 之后的情况。worker 路径没有实机验证。
- 皮肤变体的失效驱动没有游戏内验证；反射订阅 Quick Skin 的 `InternalEventBus` 依赖对方公开字节码、但文档标为 internal 的 API，没有稳定性承诺，API 变动时静默降级为"每玩家判定一次"。当前只在"未安装 Quick Skin"分支执行过该反射路径。
- 逐帧缓存的分配减少量（capability 查询约 46k 次/秒、locator scratch 约 34.5k 次/秒）来自改动测量，不是游戏内端到端性能验收；被拒绝的配置读取与皮肤解析缓存另有实测成本说明，见[实体与帧状态](../../architecture/animation/entity-and-frame-state.md#逐帧缓存与分配)。

## Context、线程与生命周期

- 异步求值没有不可变输入快照，会直接读取 live `Entity`、`level`、`Minecraft` 输入和可选模组 API；同次求值可能混入不同时间点数据，也可能违反外部 API 的线程限制。
- Forge world 中 controller、instruction、defer、config 与多 `RenderContext` 的实际 once-only 行为尚未端到端验证。`AnimatableEntity.executeMolangExp` 只把任务排入 `AnimationProcessor` 的待执行队列，队列的排空时机与同一次逻辑推进内的执行次数没有实机证据，也没有对应自动化覆盖。
- Canonical pose 可以跨兼容 pass 复用，但当前逐次 draw metadata 可能随输出一起复用；例如同帧 shadow pass 可能观察到上一 `RenderContext`。
- 同一 render target 内热替换会保留动画状态并依赖烘焙兼容；兼容性门禁和第一人称独立运行时尚缺完整闭环。
- `AnimationProcessor` 原地修改共享 snapshot 与 attribute，没有事务 staging 或模型 revision 二次校验。控制器级已经有故障隔离：`controller.process` 抛出的 `RuntimeException` 会被捕获，只跳过该控制器这一帧（其余控制器继续跑，它的姿势冻结在上一帧），日志按 1200 tick 限流；`Error` 仍然继续冒泡。因此单个谓词的 bug 不会再让整帧渲染崩掉——但被跳过的那一帧仍然是部分更新：同一帧里更早的控制器写进 snapshot 的值不会回滚。

## Molang 与同步

- 模型内 Molang 已回到本地解释器直接执行 source：加载与绑定会解析 execution-bearing 字段，求值在渲染路径上逐次进行。现在有一个动态 `query.life_time` binding 的解析/求值语义测试及 opt-in 微基准；本机合成样例约 24 ns / 32 B 每次 evaluate、2.3 μs / 1.1 KB 每次 parse。它不代表真实模型函数、完整 `MolangContext` 或 controller 总成本；表达式复杂度上限、生产热路径分布与 tick/render 影响仍未测量，Minecraft/Forge world 层也没有端到端验证。
- Roaming 的 full 与 delta 已由玩家状态报告路径携带并在消费端按协议验证，但网络 inactive storage 仍按模型派生的 32-bit 短键分组，短键碰撞和协议无变量删除语义仍未解决。
- `kinetic.sync` 只携带有限 F32 参数并把参数个数限制为 16，没有目标 identity、独立频率限制，按设计也没有 sequence、ACK、重放或持久化。实体缺失、generation/model 不一致或会话变化时事件会丢失，真实双端 relay/disconnect 路径尚未验证。

## 联动与验证

- 主线仍直接接线部分战斗、移动、载具和装备 adapter，大量目标未迁入；既有接线也未按统一输入边界完成线程审计与实机验收，不能视为正式支持。
- Java/corpus 测试已覆盖 animation/controller 的 Proto 映射与字段顺序、`Program` envelope 的 oneof tag 与 source 往返、ModelData 的双 Proto codec 互操作、内置目录索引、模型导入与 raw parse、Roaming 变量存储，以及模型声音 retention/playback/handoff 的局部边界。`MolangEvaluationBenchmarkTest` 现在覆盖一个动态 query binding，并提供 opt-in 的固定表达式 parse/evaluate 微基准；仍缺少 coded / Bedrock / hybrid controller 在真实 entity 上运行、controller config、`kinetic.sync`、多人远端 `Entity`、模型热切换、同帧多 `RenderContext`、声音 once-only/音量/host adoption、第一人称共享和长期 reference convergence 的 Minecraft 端到端测试。完整测试计数见[当前支持状态](../support-and-verification.md)。表达式解释器的广泛语义仍未覆盖。
- `BoneAttribute` 与 `BakedModel` preorder、可见性、locator 的跨语言契约缺少独立 golden 验证；布局重复定义仍有漂移风险。

模组恢复边界见[模组动画联动](../../future/mod-animation-integration.md)，渲染侧的 extract、locator 与重入缺口见[渲染已知问题](rendering.md)。
