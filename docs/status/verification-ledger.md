# 交付验证台账

> 用途：把「崩溃安全 / 内存·枪械 LOD / 性能 / 动画」四类修复与**验证证据**逐条对齐。
> 每条都给出提交、守护测试和可复现的命令，避免"改了什么"只能从对话历史里翻。

## 0. 产物与总体状态

| 项 | 值 |
| --- | --- |
| jar | `kinetic-3.0-dev-forge+mc1.20.1.jar` |
| 字节数 | 17,727,701 |
| sha256 | `ccceb1f62fe59c01b61dccfd88214e20c1ffa42372443eaf102a79d39e11cd98` |
| 测试 | 上次全量（本轮动画测试新增前）：799 tests / 22 failures / 11 skipped；本轮动画定向 7 classes pass；`processorTest`: pass |
| 构建 | JDK 17：`./gradlew build -x test --offline` pass；全量测试未在本轮重跑 |
| 文档自检 | 未运行：Python 解释器不可用；详见 §6 |

复核命令：

```bash
# Requires JAVA_HOME to point to an installed JDK 17.
LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew build -x test --offline --console=plain
sha256sum build/libs/kinetic-3.0-dev-forge+mc1.20.1.jar
./gradlew test --console=plain   # full audio verification also requires KINETIC_AUDIO_FIXTURE_DIR
python3 docs/tools/check_docs.py   # requires Python 3
```

## 1. 崩溃安全

### 1.1 顶点缓冲预留越界（曾导致启动崩溃）

- **症状**：`BufferBuilder.kinetic$reserve` 抛 `IndexOutOfBoundsException: Range [0, 0 + 20304) out of bounds for length 1536`。
- **根因**：把**顶点数**传给了按**字节**计容的 `ensureCapacity(int)`。
- **修复**：`BufferReservation.bytesFor()` 做溢出拒绝，`fits()` 复核；放不下时返回 `null` 交给 `FallbackVertexWriter`，不再"预留后丢弃"。
- **提交**：`8b48fcf`
- **守护**：`BufferReservationTest`（含常量池扫描，防止调用点再退化）

### 1.2 控制器异常隔离

- **症状**：任一谓词抛 `RuntimeException` 会直接冒到渲染帧，整个游戏崩溃。
- **修复**：`AnimationProcessor.tickAnimation` 里 `controller.process` 包 try-catch，只跳过该控制器这一帧；**只吞 `RuntimeException`**（`Error` 继续冒泡）；日志按 1200 tick 限流。
- **提交**：`a9b1509`
- **守护**：`AnimationProcessorFailureIsolationTest`
  - **行为级**：把 try-catch 抽成 `AnimationProcessor.tryProcess(...)`，用一个"只会抛异常的最小控制器"
    验证两件事——`RuntimeException` 被收成**返回值**（调用方不会被打崩），`OutOfMemoryError` 继续冒泡。
  - **结构级**：渲染循环必须走 `tryProcess` 而不是直接 `controller.process`；失败日志必须按 tick 限流。
- **边界**：`tryProcess` 之外的 `tickAnimation` 完整路径（入参是 `AnimationEvent`、`ExpressionEvaluator`、
  `ModelBones` 一整套运行期对象）仍然只能实机验证；离线覆盖到的是"异常不会从控制器这一层冒出去"。

### 1.3 Curios 跨线程访问

- **症状**：`CuriosCompat` 的 FIXME——`CuriosApi` 的 `ItemStackHandler` 非线程安全，而 native extract / 模型烘焙在 worker 上。
- **修复**：三个 `hasAnyItemEquipped*` 先过 `onRenderThread()`；非渲染线程返回 `false`（不崩）；无 GL 的离线环境探测失败时放行以保持原行为。
- **提交**：`f0f7195`
- **守护**：`CuriosCompatThreadGuardTest`（**源码级**三点断言）
- **为什么不是行为测试**：`CuriosCompat` 直接 `import top.theillusivec4.curios.api.CuriosApi`，
  离线类路径上没有 Curios，加载这个类就会 `NoClassDefFoundError`，因此无法在单测里调用
  `onRenderThread()`。这条同样只能靠源码断言 + 实机日志。

### 1.4 片段着色前的数值安全

- `kinetic.prone_roll` 经 `ease()` 的 clamp 恒落在 `[0,1]`，所以 `math.sqrt(prone_roll)` 不会产生 NaN。
- `LinearBlendTransition` 长度 0 时 `get()` 返回 1（源码里已有 `ticks != 0` 判断），不会 0/0。
- **守护**：`LinearBlendTransitionTest`（提交 `6af5296`）

## 1.5 与本任务相关的提交一览

```bash
git log --oneline 987d98e..HEAD
# a737504 挥砍/投掷重做：按世界 -Z 前方向重新求解，手真正往前挥
# 76157be 整理 PronePredicate 常量位置
# 05c7c14 趴下动作：识别改读玩家使用状态、挥砍/投掷重做、打药舒展加节奏
# ed975bc 侧躺节奏加快：站姿动作途中趴下当帧即完全侧躺
# 0d49ab6 去掉 prone 频道的冗余淡入
# a9b1509 动画控制器故障隔离
# f0f7195 Curios 装备查询加渲染线程门
# 081000a 枪械 LOD 裁决材料 + 文档断链修复
# 6af5296 换弹/掐雷姿态审查（结论：不改）+ 0 长度过渡护栏
# 1debe42 性能实测 + 本台账
```

（`8b48fcf` 的顶点缓冲修复在这段之前，可用 `git show 8b48fcf` 单独查。）

## 2. 内存 · 枪械 LOD 预热

**结论：预热已整体消除，不是"未修复"而是"移除"。**

移出的规模与完整性（可复现）：

```bash
unzip -l build/libs/kinetic-3.0-dev-forge+mc1.20.1.jar | grep -ci "gunlod\|gun_lod"   # → 0
grep -ri "gunlod\|gun_lod" src/ | wc -l                                               # → 0
find todo/gun-lod/src -name "*.java" | wc -l                                          # → 38
find todo/gun-lod/src -name "*.java" -exec cat {} + | wc -l                            # → 7215
```

| 项 | 移出前 | 移出后 |
| --- | --- | --- |
| jar 内 LOD 类 / 资源 | 38 个类 + 进度条 overlay | **0** |
| `src/` 内引用 | 接线点散布 5 处 | **0** |
| 配置项 | `gun_lod` / `gun_lod_prewarm` / `other_player_gun_lod` 等 11 个 | **0**（旧配置残留段被 Forge 忽略） |
| 预热磁盘 | 1000 把约 250 MB | **0** |
| 预热 CPU | 单核约 5%，1000 把约 17 分钟 | **0** |

因此"LOD 预热内存"这一项在当前构建里不存在可占用内存的路径。

**未做**：重做。它需要产品先回答三个问题（可辨识轮廓的底线 / 是否降级自己的枪 / 250 MB 磁盘是否可接受），
材料见 [枪械 LOD 裁决材料](../future/gun-lod-decision.md)。

## 3. 性能

`ProneAction.advance` 每个渲染帧、每个被跟踪玩家各跑一次，是动画侧最热的纯逻辑路径。
`ProneActionThroughputTest` 用 20 万次预热 + 100 万次计时量出单次成本：

```bash
./gradlew test --offline --tests "*ProneActionThroughputTest"
# 本轮本机 system-out：
# [perf] ProneAction.advance = 35.8 ns/call (1000000 calls in 35 ms)
# [perf] ProneAction.ease    = 30.8 ns/call
```

断言上限 2000 ns/call，留了约 40 倍余量吸收 CI 抖动；目的是发现"这条每帧路径上出现分配或哈希查找"
级别的退化，而不是精确 benchmark。

其余性能结论（无新增逐帧分配）：

- `PronePredicate.detect` 的物品分类只在 `isUsingItem()` 为真时执行；
- `PlayerAnimatableCapability` 的皮肤变体判定已从"每帧轮询"改为"事件 + 失效集合驱动"（提交 `987d98e` 之前的工作）。

### 本次 CPU 基线

诊断主机为 Intel Core i5-1135G7 @ 2.40 GHz、Windows 10 x64、Temurin JDK 17.0.20.1；内嵌 Windows native DLL 报告 AVX2。以下是单次本机基线，不是跨设备性能承诺。

```bash
KINETIC_BENCH_EXTRACT=1 KINETIC_BENCH_DIAGNOSTIC=1 KINETIC_BENCH_RENDER=1 ./gradlew test --offline --tests "*NativeExtractCostBenchmarkTest*"
KINETIC_BENCH_MOLANG=1 ./gradlew test --offline --tests "*MolangEvaluationBenchmarkTest*"
```

`NativeExtractCostBenchmarkTest` 用合成 opaque 模型；Extract 预热 5,000 次，计时 200,000 次并按 5 个 40,000-call batch 报告中位数。render 直接计时 `NativeRenderer.nRender` 的 JNI fallback `int[]` 输出，预热 500 次、5 个 2,000-draw batch，不包含 Java `VertexConsumer` 回放、Minecraft/Iris draw 或 GPU。

| 模型形状 | 骨骼 / cube | scheduled vertices | Extract batch 中位数 | Native render batch 中位数 |
| --- | ---: | ---: | ---: | ---: |
| tiny | 1 / 1 | 12 | 437.8 ns/call | 670.3 ns/draw |
| small | 6 / 6 | 72 | 666.9 ns/call | 783.6 ns/draw |
| mid | 16 / 16 | 192 | 873.6 ns/call | 1,588.9 ns/draw |
| large | 31 / 25 | 300 | 1,410.3 ns/call | 1,585.8 ns/draw |
| player shape | 61 / 46 | 552 | 2,372.1 ns/call | 2,605.1 ns/draw |

线程分配计数显示，Extract 原先每次稳定分配 `144 B`，来源是 Java pose/render-index 借用视图包装；按 native 地址与数量复用后，本轮稳定状态计量为 `0 B/call`。其中 player shape 从 144 B 降至 56 B 再到 0 B；Extract 耗时在各轮 batch 波动内，没有据此宣称帧耗时下降。`NativeModelStateIntegrationTest` 覆盖稳定地址复用、ShortBuffer cursor 重置、失败失效与 close。

`MolangEvaluationBenchmarkTest` 使用一个动态 `query.life_time` 变量及固定表达式。本机单次测量为 parse `2,322 ns / 1,128 B`、evaluate `23.6 ns / 32 B`；evaluate batch 约 13~36 ns，波动较大。该样本不代表完整 controller、实际模型表达式分布或生产 GC。

这些诊断不含真实模型加载、动画 controller 全链、Java 顶点提交、多实体调度、透明/PBR、多 pass、GPU 同步、MSPT 或 GC 周期；不能替代 Forge/Iris A/B 或目标设备验证。native 源码工程不在当前工作树，以上 native 数据来自预编译 DLL。

## 4. 动画

| 场景 | 修复 | 提交 | 守护 |
| --- | --- | --- | --- |
| 滑铲与趴下互斥 | `ProneAction.isProne` 显式排除 `SlideLock.isLocked`；判定抽成纯函数 | — | `SlideLockTest` / `SlideLockWiringTest` / **`ProneActionPoseGateTest`**（八道门逐条否决 + 滑铲专项） |
| 近战（趴下挥砍） | 两段交替，**直接取自 `swing:sword` 的 variant 1/2 手臂曲线**、放进侧滚 70° 的身体系（`prone:swing` 下劈 / `prone:swing:alt` 反手横斩，按挥动次数轮换）；0.45s / **13 关键帧**，前臂的 X 放开（只锁 Z 时手框只转 47°、刀根本挥不动）；逐帧手速 1.3~3.2 格、上臂 18~41°/帧、肘 −50°~−139° 单侧 | — | `ProneWiringTest`、`PronePoseDataTest`；带长刀的逐帧验收见 `prone-actions.md` §4.3 |
| 近战（站起后残留） | 挥动结束且不再趴着时 `STOP` 趴下变体（判定抽成 `shouldStopProneVariant`） | `05c7c14` | `ProneWiringTest`（结构）+ **`SwingPredicateStopRuleTest`**（行为） |
| 打药 | 0.72s 节奏、腿伸直；支撑臂平贴地面后躯干 0.94、全身最低 0.60 | `ed975bc`、`30e4950` | `PronePoseDataTest` |
| 死亡 | 布娃娃捕获死亡瞬间的 KINETIC 骨骼，不退回僵直站姿 | — | `KineticRagdollMathTest`（映射/零跳变/关节范围） |
| 侧躺过渡 | 站姿动作途中趴下**当帧即完整侧躺**（`CATCH_UP_PROGRESS=1.0` + 频道过渡 0），进入 0.15s / 退出 0.24s | `ed975bc`、`0d49ab6` | `ProneActionTest` |
| 站起 / 死亡时的收尾 | 不再趴着时走 **SNAP 0.08s** 而不是 EXIT 0.24s；`armedCatchUp` 留住"动作是站着开始的"，再趴下当帧到位 | — | `ProneActionTest#standingUpSnapsTheRollBackWithinTheSnapWindow`、`#standingUpKeepsTheActionKindSoLyingDownAgainIsImmediate` |
| 换弹 / 掐雷 | 支撑侧（左）手臂改为平贴体前并参与贴地补偿；原"手肘撑地"姿态只算进补偿会把躯干顶到 2.29，更像悬浮 | `30e4950` | `PronePoseDataTest` |
| 趴下动作识别 | 打药/扔雷原先只靠联动模组接口与分类表，没进表的消耗品会被判成"没有动作"而退回通用姿势（手臂继续播站姿动画）。加了一层**物品注册名关键词**兜底（grenade/frag/medkit/syringe/inject…），投掷也按手里的东西判断 | — | `PronePredicate` 源码护栏 + `ProneSwingAlternationTest` |
| 布娃娃没生成 | Ragdollified 1.2.2 由服务端权威生成；客户端死亡入口仅注入姿态并等待 6 tick，未收到服务端布娃娃才兜底 spawn。排队后验证 20 tick，仍不存在就登记回退、恢复死亡动画和尸体可见性 | — | `RagdollDeathYieldTest`、`RagdollTuningTest` 定向测试通过（禁止立即客户端 spawn、独立探测待处理状态） |
| 跨姿态接力 | 趴着挥到一半站起来（或反向）时，新动画从**同一进度**继续（`seekTo` + `playAnimationSeeked`），不再回到第 0 帧 | — | `AnimationSeekTest`、`ProneSwingHandoverTest` |
| 侧躺时头的侧倾 | `Head` 的 Y（绕身体长轴）从默认 +20（实测侧倾 **84°**，像整个头侧躺在耳朵上）收到目标 **45°**；X/Z 由 `prone_head_tilt.py` 联立反解，视线俯仰与横偏都不变 | — | `PronePoseDataTest`（只钉通道存在）；数值见 `prone-actions.md` §2.1 |
| 支撑侧手臂入地 | 贴地补偿原先排除全部四条手臂，六个动作左臂入地 1.09~2.17；改为只排除右臂与头，躯干压在支撑臂上 | `30e4950` | 同上 |

## 5. 已知限制（明确未做）

| 项 | 原因 |
| --- | --- |
| 枪械 LOD 重做 | 需产品裁决，见上文 |
| 第一人称手臂 / 背景渲染 | `RenderArmEvent` 与背景层调用仍被注释；启用必须实机验证 |
| Curios worker 路径 | 已加线程门，但 worker 上"条件为 false"的观感没有实机验证 |
| 动画求值的事务性 | 控制器级已隔离，但被跳过的那一帧仍是"部分更新"（同帧更早的控制器写入不回滚） |

## 6. 本轮执行记录（2026-11-15）

- 聚焦验证通过：`NativeExtractCostBenchmarkTest`、`NativeModelStateIntegrationTest`（3 tests）、Molang benchmark/语义测试（2 tests）和 `ProneActionThroughputTest`（2 tests）。`processorTest --offline` 通过。
- 本轮动画定向验证通过：`ProneActionTest`、`PronePoseDataTest`、`PronePredicateMappingTest`、`ProneSwingAlternationTest`、`ProneSwingHandoverTest`、`ProneWiringTest`、`SwingPredicateStopRuleTest`。
- 本轮 JDK 17 构建 `./gradlew build -x test --offline` 通过，产物字节数和 SHA-256 见 §0；Javadoc 生成过程报告了生成源码与注释警告，但任务未失败。
- 全量 `test` 的最近记录为动画测试增加前的 799 tests、22 failures、11 skipped，本轮未重跑全量。已知失败中，20 项音频 contract 测试因未设置 `KINETIC_AUDIO_FIXTURE_DIR` 且当前工作区找不到 `opus-under.ogg` / `vorbis-under.ogg` / `opus-input-rate.ogg` fixtures 而失败；`FinalClosureSupervisorTest.successfulGitStderrIsRetainedButExcludedFromStdoutPaths` 在 Windows 上实际得到带换行的 `guide.md\n`；`ModelExporterTest.currentContainerWrittenWithExportSuffixIsRediscoveredAsDirectArtifact` 可在隔离运行中复现，但诊断确认导出文件存在且已出现在 inventory，失败来自测试直接比较 8.3 短路径与 `toRealPath()` 长路径，属于路径规范化测试问题，不是生产发现遗漏。该测试未在本任务范围内修改。
- `git diff --check` 通过。`docs/tools/check_docs.py` 与 `check_complexity_relationships.py` 未能运行：没有可用 Python 解释器，`python` 不存在，`python3` 的 WindowsApps 别名以错误码 9009 退出。
