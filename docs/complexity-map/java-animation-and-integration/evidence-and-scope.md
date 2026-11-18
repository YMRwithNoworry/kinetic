# 证据、覆盖与根依据

分类、证据等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。

## 覆盖

| 已建图内容 | 未闭合 / 未检查 |
|---|---|
| Entity、异步/多 pass、动作门禁、defer 排空、模型声音 handoff、混合补丁、checker、输出桥；AN-01–06、08–13、19–20 | Forge/联动、第三方线程安全、完整数值/视觉、真实 OpenAL/设备容量未验；AN-19 在 Forge 实机中的排空时机未核验 |
| — | **已知建图缺口：TaCZ 枪械 LOD 整组机制尚未建图**。范围包括可选扩展隔离与自建几何（`TACZCompat` / `TaczGunLodBuilder`）、四阶段减面（`GunLodDecimator`）、两级缓存与提前烘焙（`BakedModelCache` 复用 + `GunLodCacheKey` / `GunLodFactory` / `GunLodRegistry`）、后台预热（`GunLodPrewarm`：双档节奏、给按需构建让路、按代次取消）、距离门禁与"自己的枪不降级"（`GunLodDistance`）。该组与 NR-04/05 共享同一份烘焙磁盘缓存，跨域边需一并登记。 |

**这个缺口当前不能靠维护地图补齐。** 该组机制在[产品决策树](../../product-decisions/README.md)里没有对应的根键：可选联动的**失败**边界有 `D-EXT` / `D-FAIL`，但"为什么由 KINETIC 自建简化几何、以及它相对 TaCZ 自带 LOD 的义务是什么"没有任何已裁决的产品判断。按[维护规则](../governance/maintenance.md)，缺少必要产品义务时应列出最小补齐内容并暂停依赖该缺口的判断，不能从现存代码猜造产品决定。因此本页只登记范围与缺口，不分配 AN 节点、不写因果边、不计入机制数；架构行为见[枪械 LOD](../../architecture/integration/gun-lod.md)。最小补齐内容是一份产品裁决：枪械 LOD 是"必须保持可辨识轮廓的降级表现"还是"纯性能优化、允许 best-effort"——它决定这组机制的必要性分类与删除失败描述。

**受伤外观（玩家模型上的伤口叠加层）本次不分配独立机制节点。** 判断依据是它不引入新的协调机制：档位是每帧现算的纯函数，遮罩是每个档位一张不可变纹理（复用宿主的纹理注册生命周期），绘制是复用已提取的 `GeoModelState` 再提交一遍（复用 AN-11 / AN-12 的宿主输出路径与现有的实体 layer 遍历）。它没有新的 owner、生命周期交错、跨子系统边或恢复分支，属于已建图机制的消费者。产品义务已在[产品决策](../../product-decisions/decisions/player-wound-overlay.md)中裁决，机制说明见[受伤外观叠加层](../../architecture/rendering/wound-overlay.md)，当前缺口见[渲染已知问题](../../status/known-issues/rendering.md#受伤外观)。若后续评审认为"同深度第二遍绘制 + 显式刷批"构成独立的绘制交接义务，应为其分配 AN 节点并登记与 AN-11/AN-12 的关系。

**真实相机绑定（`CameraBindGeometry` / `CameraBindSolver` / `RealCameraBindSource` / `RealCameraBindingInner`）本次不分配独立机制节点，且属于净归约。** 它删除的是一条既有协调机制：过去绑定正确性依赖"KINETIC 这一帧一定被画过"，于是对方要在每帧、在关卡渲染之外把模型重画 4~8 遍并捕获顶点，这条路径同时引入了额外绘制、与正常帧不一致的渲染上下文，以及"没画出来就判失败"的恢复分支。改后它只消费已建图产物：原生烘焙几何（NR-04/05）、逐骨骼姿态视图（NR-08/09）、`GeoReplacedEntityRenderer#applyModelPose` 的姿态应用与 `GeoRenderData`（AN-11/12 的宿主输出路径）。没有新的 owner、生命周期交错、跨子系统边或恢复分支——失败一律让位给对方自己的路径，不新增状态机。因此本页只登记归约事实，不分配 AN 节点、不写因果边、不计入机制数。产品义务已在[产品决策](../../product-decisions/decisions/camera-binding.md)中裁决，机制说明见[真实相机绑定](../../architecture/integration/camera-binding.md)，当前缺口见[渲染已知问题](../../status/known-issues/rendering.md#相机绑定)。若后续评审认为"与外部相机模组共享同一帧的最终位姿"构成独立的接入义务，应为其分配 AN 节点并登记与 AN-11/AN-12 的关系。

**TaCZ 玩家弹道 OBB 目前不是生产机制。** 产品边界已在 [DD.rendering-only-replacement](../../product-decisions/decisions/visual-gameplay-boundary.md#ddrendering-only-replacement) 下记录为 TaCZ 专项契约，但服务端仍不求值 KINETIC 骨骼姿态，实际行为继续使用 TaCZ AABB。当前没有可分配的 AN 节点或跨子系统因果边；只有出现真实服务端形状 owner 与 TaCZ 命中接入机制后，才按其实际协调关系更新机制图。现状见[TaCZ 命中架构](../../architecture/integration/tacz-hit-detection.md)与[支持缺口](../../status/known-issues/animation.md#tacz-player-projectile-hit-detection)。

本主题记录 14 个生产机制。声音 handoff 双序已有局部自动化；Forge once-only、真实 host adoption/容量、第三方 consumer、真实多 pass 画面和跨线程可见性仍需运行验证。上面第二行的缺口不计入本主题机制数——它列的是"待建图范围"，不是已建图节点。

## 证据入口

[机制正文](mechanisms.md)逐项记录 Fact、Inference、Hypothesis 与直接删除失败；[因果图](causal-graph.md)唯一维护 AN 内部边，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题消费的根键定义与权威链接见[根依据总表](../governance/root-register.md)。

| 类别 | 本地使用的键 |
|---|---|
| 动画产品约束 | `D-ENTITY`、`D-EFFECT`、`D-SOUND`、`D-SAMPLE`、`D-CONTROLLER`、`D-VANILLA-ACTION`、`D-AN-FALLBACK` |
| 失败与宿主边界 | `D-EXT`、`D-OUTPUT`、`D-HOST`、`D-LOOP` |
| 架构选择 | `A-AN-01`、`A-AN-02`、`A-AN-03`、`A-AN-04`、`A-NATIVE`、`A-DEFER` |
| 未闭合根 | `H-BLEND`、`H-FENCE` |

模型资源 lease、native view 与 worker completion 是跨子系统前提，不转移到 AN 成为第二 authority。
