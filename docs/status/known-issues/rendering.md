# 渲染已知问题

本页只记录当前实现可证实的渲染缺口。目标与正常语义见[渲染架构](../../architecture/rendering/README.md)。

## 视觉与接入

- 透明排序只覆盖单次模型 draw；跨实体、跨模型和跨 draw 的次序仍由上层决定。Iris shadow 不执行透明排序。
- PBR 效果依赖 Iris 版本、shader pack 和 companion texture 接入，不能仅凭 baked tangent 存在保证一致。
- 当前实际接通的骨骼附着点 layer 只有右手持物；副手、头部、鞘翅、肩部和背包等仍未进入完整主链，第一人称手臂与背景入口也未迁移完成。

## 正确性与失败处理

- `ModelState::Extract` 会先使旧状态失效；失败后 `GeoModelState` 没有完整失败分支，同一逻辑帧可能不再重试。Native render 失败时不会改用其他输出路径或 `VertexConsumer` fallback，本次模型直接无顶点。
- Serialized baked cache payload 不能独立证明 SIMD capability 匹配；读取虽校验结构、层级、索引和计数，却未重新验证几何浮点值的有限性及语义域。Bake 对极端有限输入派生的 plane / tangent 也缺少完整结果域验证。
- 上层必须提供与 position matrix 匹配的 normal matrix；native 只校验数值有限，不验证二者一致，该组合目前也没有端到端验证。
- `BakeModelOptions.force_translucent` 可能让原本 opaque 的骨骼进入透明分区，却未同步其透明深度准备条件。当前 Java 主加载路径不启用该选项，启用前需补齐最终排序验证。

## 并发与性能

- `ParallelExecutor`、translucent scratch、`VertexConsumer` fallback 与 `NativeRenderer` 的共享 matrix scratch 都不可重入，多个 `renderer::Render` 必须全局串行。`ModelState` 原地复用自身 pose 与索引存储，Java view 只在该状态的有效期内可读，因此同一输出槽的 Extract、Render、换模与释放必须串行。
- 当前 opt-in CPU 微基准只覆盖合成 opaque 模型的 Extract 和 direct JNI fallback 顶点输出；不包含 Java `VertexConsumer` 提交、Minecraft/Iris pass、GPU draw、真实多实体调度或透明/PBR 场景，不能作为端到端性能或视觉支持证据，详见[验证台账](../verification-ledger.md)。
- 调度按不可拆分 `CubeGroup` 数而非实际 quad、PBR 或剔除成本分配任务，复杂模型可能出现 worker 尾部不均衡。
- 剔除分区按最大可见容量预留，并以零值填充未使用槽位，这是固定 offset 的当前代价。

## 扩展接线

`RegisterRenderStateModifierEvent` 和 `RenderStateModifier.apply()` 当前只有声明与容器代码，生产路径没有发布该注册事件或调用 modifier。`@ParallelInvoke("entity")` 注解不能作为该扩展已接通的证明；已存在的 locator、模型和 layer 事件窗口见[游戏与扩展接入](../../architecture/integration/README.md)。

## 布娃娃身体

玩家死亡布娃娃的可见身体接管见[布娃娃身体接管](../../architecture/integration/ragdoll.md)。当前状态：

- 接管依赖提供方的玩家身体渲染路径；提供方版本变化导致注入点失配时，本功能静默回退到提供方原版人形，这是预期降级而非缺陷。现在这条降级是被**强制**的：只有观察到注入点真的被调用过才允许接管，所以失配时不会出现"两套模型同时绘制"。没有任何自动化手段能在版本变化时提前发现失配（只能从日志里看出接管从未生效）。
- 姿态映射、零跳变与关节范围语义由 `KineticRagdollMathTest` 覆盖；次级动力学的稳定性、防穿透与真实观感没有任何自动化覆盖。
- 断肢仍由提供方按原版人形绘制，因此玩家模型与断肢外观可能不一致；KINETIC 侧只保证断肢部位不再被画到别的布娃娃的位姿上。
- 骨架与内置 Steve/Alex 不同的作品模型不进入接管，直接使用提供方原版人形。
- 与皮肤类模组的组合、护甲与饰品的对齐、以及多具布娃娃同时存在时的帧率影响，都尚未在真实 Forge world 中验收。
- **绘制距离改为跟随 Ragdollified 的客户端配置**（此前是 KINETIC 自己的 `Ragdollified.RenderDistance`，默认 64，已删除）。这条改动没有实机验收：读的是提供方的字段名，字段缺失时退回 128。

## 相机绑定

真实相机的相机绑定解算见[真实相机绑定](../../architecture/integration/camera-binding.md)。当前状态：

- **没有任何游戏内验收。** 结论全部来自单测与对真实烘焙管线的断言：`CameraBindGeometryNativeTest` 在内置 Steve 头部同构的模型上验证真实相机默认目标的 UV 落在头骨正面与头顶、解出的位置是眼睛高度 `(0, 1.7, -0.25)`，并验证骨骼姿态与模型姿态的复合；`CameraBindGeometryTest` / `CameraBindSolverTest` 覆盖 UV 命中、边界包含、面内插值、法线、退化输入与目标缓存。`analysis/realcamera_bind_preview.py` 用另一套独立实现复算同一组默认目标，结果与上述断言逐项一致（不一致即报错），并出图 `analysis/out/realcamera/bind-point.png`。这些能证明几何与坐标系正确，但不能替代真实 GPU 上"相机确实贴在眼睛上、快速转身时不漂"的观感验收。
- **与真实相机的实际交互没有实测。** 优先级 `0` 高于对方自带的 `-100`，因此只要本层给出结果，对方那条"每帧重画模型 4~8 遍"的路径就不会执行——这一点是从对方的 `RealCameraAPI.computeBindResult` 取第一个可用结果的实现推出来的，没有在真实会话里核对过对方的调用次数。
- **对方版本变化时本层会整体让位。** 反射/链接失败时只记一条警告，退回对方自带路径；但"对方自带路径在模型本帧没被画出来时会失败并自我关闭"这个原始问题会随之回来。没有任何自动化手段能提前发现这种变化。
- **死亡后改绑布娃娃依赖 Ragdollified 的接管状态。** 只在 KINETIC 确实接管了这具尸体时才切换；接管让位（骨架无法映射、提取失败、超出距离）时仍走玩家模型那条路径，而此时玩家模型已被提供方摘掉，绑定会失败——这是已知的、尚未处理的组合情形。
- **头部内壁的遮挡只靠强制背面裁剪。** 提供方的绘制契约是"丢掉头部"，而 KINETIC 整具尸体只有一次原生提交，分不出"只画身体"，因此改为在相机位于头部内时强制裁剪背面。默认配置本来就裁剪，所以这条只在用户关掉 `CullBackFaces` 时生效，且没有实机验收。
- 第三人称、旁观、载具等非第一人称情形下真实相机不接管相机，本层也不会被调用。
- 目标解析失败时会按"贴图 + 模型"记一次日志；日志本身没有自动化覆盖。

## 受伤外观

受伤外观的机制见[受伤外观叠加层](../../architecture/rendering/wound-overlay.md)。当前状态：

- **没有任何游戏内视觉验收。** 结论全部来自离线软件光栅渲染：`analysis/wound_preview.py` 把 Java 侧 `WoundMaskPainter` 导出的两层遮罩按同一套深度裁决合成到皮肤上，再用 `analysis/anim_render.py` 出图（`analysis/out/wounds/`）。这能证明遮罩内容、UV 对齐、两层对应与合成颜色符合预期，但不能替代真实 GPU 上的裁剪、混合、深度相等测试、批处理顺序与光照验收。
- **"贴外层还是内层"完全依赖深度相等测试（`GL_EQUAL`），这一条只有推理没有实测。** 依据是：身体那一遍与叠加层那一遍用的是同一份原生顶点数据与同一套矩阵，因此同一像素的深度值逐位相同；第二层皮肤不透明时深度缓冲里留下的是第二层几何的深度，相等测试通过，透明时留下的是基础层的深度，相等测试判掉。若某个驱动/精度配置下两者不逐位相同，表现为"外层皮肤上的血整片消失"或"透明处仍然浮着血"，不是崩溃。没有游戏内 A/B。
- 身体走透明渲染类型（`ForceCutoutRenderer=false`）时不写深度，那种配置下自动退回 `LEQUAL`，代价是外层透明时那层血会浮着。这条降级路径也没有实测。
- **与 Visual Health 的比对是离线复现，不是同场对照。** `analysis/wound_vh_reference.py` 按 VH 自己的算法（8×8 格洗牌、格心 + nextInt(17)-8 抖动、generic 素材、0.30~0.50 不透明度、`base·(1-ratio)+wound·ratio` 合成）在 Python 里重跑一遍并出同样的图；两边档位 1~5 的叠加像素是 160/269/456/634/798 对 215/381/590/727/892。这能证明密度量级一致，但不能证明两个模组在同一帧、同一光照下的观感一致；VH 的素材是按 `ResourceManager.listResources` 的映射顺序取的，脚本里按文件名排序，逐像素不会相同。
- 叠加层用的是「混合 + alpha 裁剪 + 写深度 + 不排序」的自建 RenderType，且分两层各提交一遍（见[受伤外观叠加层](../../architecture/rendering/wound-overlay.md)）。这条路径在真实 GPU 上没有验收：混合与深度写入的组合、"先刷身体那一批再画叠加层"的顺序、以及每名受伤玩家多两次模型提交的实际帧时间，都只有离线推理支撑。风险表现是伤口被身体盖住或深度写坏，不是崩溃。
- **第一人称手臂看不到伤口**：KINETIC 的第一人称手臂模型渲染入口在仓库里是整段注释掉的 TODO，游戏内显示的是原版手臂。接好伤口只是在入口恢复时不必再改一次。
- 第三方模型默认不画伤口（UV 布局不保证对齐），开启后伤口位置是任意的。
- 距离之外不画、隐身不画、满血不画这三条只有单测覆盖，没有实机确认。
- 与 Ragdollified 的组合只做了"尸体按最高档绘制、身份随死亡姿态保存"的结构性处理；尸体上的伤口观感没有实机验收。
- **头部平衡只有离线判断。** 当前头部权重减半、尺寸乘 0.75、血痕概率减半，依据是 `analysis/out/wounds/head-closeup.png` 这一组头部特写；"看不出是谁"的门槛是主观的，需要真人在真实皮肤上确认。
- **单处伤口的大小没有真人门槛。** 上一版在躯干上放了一块半径 3 格以上的血泊来制造冲击力，被判定为"真实度反而下降"（那块血泊吃掉了整个躯干，别处没余地）。现在只有三种小尺寸，单处面积上限由 `noSingleWoundIsLarge` 单测钉住（每处平均 ≤ 44 像素），但"多大算合适"仍需真人判断。

## 背面裁剪与枪械 LOD

- `CullBackFaces` 默认关闭：native 烘焙对封闭六面盒已做内部剔除，而枪包普遍含刻意单面的 cube；开启后这些面被删成破洞（ak47 169 px、hk416d 26 px、`eos_helenas_nail` 2083 px）。Java 侧没有只提交裁剪顶点的安全路径。没有任何游戏内 A/B 或真实 GPU 视觉验收，结论来自离线几何剔除与软件渲染。
- 枪械 LOD 的 UV 取自供体方块，细部会抹平成色块（导轨槽、螺丝、握把纹路、机匣刻字），近看块状、LOD 距离下可接受。实测 texel 拉伸 ak47 p50 1.62 / p90 4.69 / max 36.50、hk416d p50 1.25 / p90 3.42 / max 13.62（模型单位 / 声明 texel）；`barrel4` 的 36.5 在该骨骼内没有更好的供体，不可再降。
- LOD 贴图验证使用真实 TaCZ 贴图时 ak47 与 hk416d 的白/未贴图像素为 0.000%，且无 UV 越界；但验证是软件最近邻采样，没有 mipmap 与各向异性过滤，也没有真实 GPU 验收。
- eos 枪包贴图不可得，因此 eos 只做了结构性验证，没有贴图渲染；TaCZ 自己的 `lod` 模型也没有对比。
- 引入粗体素网格折叠后，LOD 不再是完整模型的顶点子集，"严格子集 / 零独占像素"这类旧结论不再适用；当前的包含关系是精确旋转 AABB 上的结构论证，不是逐像素验收。
- **阶段 3 的方向判定修过一次，此前所有离线渲染都是按错的判定标定的。** X 轴那一对条目的实际半轴与直觉相反（`GeoBuilder` 取反 X 并把 west/east 贴图对调），旧实现删掉的是外侧可见面、留下的是内侧看不到的面；独立的 Python 参考实现有同一个错，所以两边互相印证、谁也没发现。修正后 ak47 的 LOD 面数从 1208 变成 1210，`analysis/out/` 下的贴图渲染已按新几何重新生成（白/未贴图像素仍为 0.000%）。**但没有做"逐像素轮廓差"的前后对比**——`white texel` 这个指标只统计采样到的 texel 是否为白，不检测几何缺失，所以它不能证明没有破洞。真正的依据是直接读 `GeoBuilder.addPerFaceQuads` 与 p1..p8 的坐标定义推出的映射，加上 `GunLodFaceCullingTest` / `GunLodAuditRegressionTest` 里按该映射写的定向断言。
- **磁盘缓存、整包复用与后台预热没有游戏内验收。** 单测覆盖了缓存键的敏感性与稳定性、锚点附属文件的往返与拒绝、损坏缓存的降级、预热的两档节奏与让路规则、枪包指纹对内容/增删/同长度替换的敏感性、枪包根目录解析、预热索引的持久化与损坏降级，以及"哪些枪该跳过、跳过后进度怎么算"的整包复用判定；原生测试覆盖了"写进磁盘的几何读回来与当场烘焙逐顶点一致"。但以下都只有离线依据，没有实机测量：命中路径（不解析 Bedrock JSON、不减面、不解码 PNG）在真实资源管理器下的耗时、枪包指纹对真实枪包目录的扫描耗时、预热在低配机上对帧时间的实际影响、预热吞吐（主菜单全速一轮 / 进世界后约单核 5%）、以及"枪包更新后第一局仍可能冷启动"的窗口长度。真实 TaCZ 枪 id → display 查询也无法在单测中覆盖。
- **枪包指纹有一处理论上的漏判。** 目录枪包用 `(相对路径, 大小, 修改时间)` 判定，因此"内容变了、但每个文件的大小与修改时间都没变"（例如用 `cp -p` 或解压时保留时间戳覆盖）会被判成未变，从而跳过预热。这不影响正确性——按需路径永远重算真实的缓存键——但会让那一把枪退回按需异步烘焙。压缩包枪包对整个文件做哈希，没有这个漏判。

## 待验证场景

Native renderer 尚未形成可作为支持声明依据的自动化回归与视觉验收闭环。

在声明支持前，Minecraft 运行验证至少应覆盖：`level` entity 同帧多 pass、`inventory` / `paperDoll` context 的 mutable 输出、本地第一人称 `irisShadow`、模型热切换、`VertexConsumer` fallback、透明与 PBR、非均匀缩放及各 locator layer。还应验证 locator mapping 始终读取对应 native `ModelState` 的当前 pose，且旧 Java view 不跨越 Extract 或 close 使用。Packed normal 的编解码约定见[顶点输出](../../architecture/rendering/vertex-output.md)，约定匹配不能替代 fallback 与 direct 的完整视觉等价验收。视觉验收应比较 Vanilla、Iris 与 Blockbench 基准，并区分几何语义偏差和 shader / 光照环境差异。
