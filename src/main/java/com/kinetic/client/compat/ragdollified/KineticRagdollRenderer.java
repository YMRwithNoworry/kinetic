package com.kinetic.client.compat.ragdollified;

import com.kinetic.Kinetic;
import com.kinetic.client.skin.DefaultPlayerSkins;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.config.ClientConfig;
import com.kinetic.event.api.SpecialPlayerRenderEvent;
import com.kinetic.geckolib3.core.util.Color;
import com.kinetic.client.wound.WoundFeature;
import com.kinetic.client.wound.WoundRenderer;
import com.kinetic.client.compat.realcamera.RealCameraBindSource;
import com.kinetic.geckolib3.geo.IGeoRenderer;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.natives.render.NativeRenderer;
import com.kinetic.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollCamera;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 用 KINETIC 自己的模型绘制 Ragdollified 的玩家布娃娃。
 *
 * <p>Ragdollified 的物理与网络只支持 6 个刚体（{@code MAX_PARTS = 10} 且玩家固定走 6 段人形骨架），
 * 无法承载 KINETIC 的 61 根骨骼。因此这里不改它的物理，而是<b>接管绘制</b>：
 * 读取它已经算好的刚体位姿，驱动 KINETIC 模型的 6 根大关节骨骼，细分子骨骼由
 * {@link KineticRagdollState} 的次级动力学补全。Ragdollified 自身的人形身体由 Mixin 跳过，
 * 护甲、饰品与血迹仍按原样绘制。
 *
 * <p>本类只在 Ragdollified 存在时被加载（见 {@link RagdollifiedCompat#init()}）。
 */
public final class KineticRagdollRenderer {
    /** 布娃娃 id → KINETIC 侧状态。 */
    private static final Map<Integer, KineticRagdollState> STATES = new HashMap<>();
    /** 死亡瞬间捕获的 KINETIC 姿态：实体 id → 骨骼属性副本。 */
    private static final Map<Integer, CapturedPose> CAPTURED = new HashMap<>();


    /**
     * 死亡瞬间捕获的全部绘制输入。
     *
     * <p>不能只存骨骼姿态：客户端玩家实体死亡 20 tick 后会被移除（{@code LocalPlayer#tickDeath}），
     * KINETIC 随之重置该玩家的能力（模型被卸载）。若尸体仍要继续用 KINETIC 模型绘制，就必须自带模型来源、
     * 缩放与贴图，否则只能退回提供方的原版人形。
     */
    private record CapturedPose(GeoModel source, float[] attributes, float widthScale, float heightScale,
                                @Nullable ResourceLocation texture, String modelId, long capturedAtMs) {
    }

    /**
     * 本帧绘制该布娃娃所用的模型来源：优先实时能力，其次死亡瞬间的快照。
     *
     * <p>{@code rigModel} 为 {@code null} 表示骨架映射需要临时从 {@code geoModel} 重建（快照路径）；
     * 该重建只在建立状态时发生一次，不进入逐帧路径。
     */
    private record ModelSource(GeoModel geoModel, @Nullable AnimatedGeoModel rigModel, float widthScale,
                               float heightScale, @Nullable ResourceLocation texture, String modelId) {
    }

    /** 每个降级原因只记录一次，避免刷屏；用户日志里出现哪一条即说明接管为何让位。 */
    private static final java.util.Set<String> LOGGED_BAILS = new java.util.HashSet<>();

    private static long lastFrameNanos;

    private KineticRagdollRenderer() {
    }

    /** 以 HIGHEST 优先级注册：必须先于 Ragdollified 建立本帧的接管状态。 */
    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, KineticRagdollRenderer::onRenderLevel);
    }

    /**
     * 死亡瞬间捕获 KINETIC 当前正在绘制的姿态。
     *
     * <p>这是「零跳变」的来源：布娃娃的第一帧直接从这份姿态出发。除了骨骼姿态，还一并捕获
     * 模型来源、缩放与贴图，因为客户端玩家实体在死亡 20 tick 后会被移除、KINETIC 会随之卸载该
     * 玩家的模型；尸体必须能在失去实时能力之后继续绘制。
     */
    public static void capturePose(AbstractClientPlayer player) {
        if (!isEnabled()) {
            // 接管关闭时不需要这份快照；继续存下去就是每死一次多留一份模型引用与骨骼数组，
            // 而 pruneCaptures 在关闭分支上根本不会被走到。
            return;
        }
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap == null || !cap.hasRenderableModel()) {
            return;
        }
        try {
            var source = cap.getKineticGeoModel();
            var model = cap.getLoadedGeoModel();
            if (source == null || model == null) {
                return;
            }
            CAPTURED.put(player.getId(), new CapturedPose(source, model.getBoneAttributes().clone(),
                    cap.getWidthScale(), cap.getHeightScale(), resolveTexture(player, cap),
                    cap.getModelId(), System.currentTimeMillis()));
        } catch (RuntimeException failure) {
            // 能力可能正处于半重置状态：捕获失败只意味着退回实时能力绘制，绝不能打断客户端 tick。
            Kinetic.LOGGER.warn("Failed to capture the KINETIC death pose of {}",
                    player.getName().getString(), failure);
        }
    }

    /** 能力自身的贴图；能力尚未挂上渲染目标时返回 {@code null}。 */
    @Nullable
    private static ResourceLocation capabilityTexture(
            com.kinetic.capability.PlayerAnimatableCapability cap) {
        try {
            return cap.getTextureLocation();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 本帧是否由 KINETIC 接管了这个布娃娃的绘制；供 Mixin 判断是否跳过原版人形身体。 */
    public static boolean isHandled(int ragdollId) {
        return STATES.containsKey(ragdollId);
    }

    /**
     * 供 Mixin 记录「当前正在绘制哪个玩家布娃娃」。
     *
     * <p>没有这一层标记就无法区分 7 参数 {@code renderHumanoidPartPhysics} 的调用来自玩家、
     * 怪物还是村民；只有玩家布娃娃且已被 KINETIC 接管时才跳过原版身体。
     */
    private static int currentPlayerRagdollId = -1;

    /**
     * Mixin 是否真的注入成功过。
     *
     * <p>三个注入点都是 {@code require = 0}：Ragdollified 改了签名时它们会静默失配，而
     * {@link #beginPlayerRagdoll} 就不会被调用。若此时 KINETIC 照旧接管并绘制，提供方那边因为
     * 拿不到接管状态、照样画自己的原版人形，同一具尸体会出现<b>两份身体</b>。
     *
     * <p>所以只有"见过 Mixin 真的被调用"之后才允许接管。失配时本字段永远为 false，
     * KINETIC 一次都不画，完整回退到提供方的原版人形——这正是文档承诺的降级行为。
     * 代价是会话里第一具布娃娃晚一帧才被接管，那一帧由提供方画。
     */
    private static volatile boolean mixinObserved;

    public static void beginPlayerRagdoll(ClientRagdoll ragdoll) {
        mixinObserved = true;
        currentPlayerRagdollId = ragdoll.getId();
    }

    public static void endPlayerRagdoll() {
        currentPlayerRagdollId = -1;
    }

    public static boolean shouldSkipHumanoidBody() {
        int id = currentPlayerRagdollId;
        return id >= 0 && STATES.containsKey(id);
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            // 退出世界 / 换维度：这里既不会绘制，也等不到 pruneStates，所以必须主动放掉
            // 全部原生状态与捕获快照，否则它们会一直留到下一个世界的第一帧。
            clearStates();
            CAPTURED.clear();
            return;
        }
        if (!RagdollifiedCompat.isAvailable() || !isEnabled()) {
            clearStates();
            // 关闭接管时 capturePose 仍可能被调用（它只看提供方的配置），所以这里也要清捕获，
            // 否则每死一次就多留一份模型引用与骨骼数组，整局都不会回收。
            CAPTURED.clear();
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L
                ? 1f / 60f
                : Math.min((now - lastFrameNanos) / 1.0e9f, 0.1f);
        lastFrameNanos = now;

        var poseStack = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        double distance = renderDistance();
        double distanceSq = distance * distance;

        // 整段都包起来：ClientRagdollManager / 提供方的 getter 也在循环条件与循环体内，
        // 它们抛错不能把整帧渲染带下去。
        try {
            for (ClientRagdoll ragdoll : ClientRagdollManager.getAll()) {
                if (!ragdoll.isPlayer()) {
                    continue;
                }
                var torso = ragdoll.getSmoothedTransform(com.raiiiden.ragdollified.RagdollPart.TORSO.index);
                if (torso == null) {
                    // 连躯干位姿都没有就无法定位模型。必须同时放掉接管状态：否则 Mixin 那边
                    // 仍然认为"这具尸体由 KINETIC 接管"，会跳过提供方的身体，而 KINETIC 又不画，
                    // 结果是一具完全看不见的布娃娃（护甲还会照常画，看起来是浮空盔甲）。
                    dropState(ragdoll.getId());
                    continue;
                }
                double dx = torso.position.x - camera.x;
                double dy = torso.position.y - camera.y;
                double dz = torso.position.z - camera.z;
                if (dx * dx + dy * dy + dz * dz > distanceSq) {
                    // 超出 KINETIC 的绘制距离：同样必须放掉接管状态。提供方自己的绘制距离默认更远
                    // （128 > KINETIC 默认的 64），所以"KINETIC 不画、提供方以为 KINETIC 在画"这一段是真实存在的。
                    dropState(ragdoll.getId());
                    continue;
                }
                try {
                    render(ragdoll, torso, poseStack, buffers, camera, deltaSeconds);
                } catch (RuntimeException failure) {
                    // 单个布娃娃绘制失败不能影响整帧：丢弃状态并让 Ragdollified 恢复原版人形。
                    dropState(ragdoll.getId());
                    Kinetic.LOGGER.warn("Failed to render a KINETIC ragdoll body", failure);
                }
            }
            pruneStates();
            pruneCaptures();
        } catch (Throwable failure) {
            // 提供方换了实现导致 getter 抛错时，宁可这一帧什么都不画，也不能让异常逃进渲染循环。
            Kinetic.LOGGER.warn("Failed to walk the ragdoll list", failure);
        }
    }

    private static void render(ClientRagdoll ragdoll, RagdollTransform torso,
                               PoseStack poseStack, MultiBufferSource buffers,
                               net.minecraft.world.phys.Vec3 camera, float deltaSeconds) {
        int ragdollId = ragdoll.getId();
        if (!mixinObserved) {
            // 注入点还没生效过（首次布娃娃的那一帧，或者 Ragdollified 改了签名）。
            // 此时接管只会画出第二具身体，所以这一帧交给提供方。
            dropState(ragdollId);
            return;
        }
        AbstractClientPlayer player = findPlayer(ragdoll.getPlayerUUID());
        var captured = CAPTURED.get(ragdoll.getOriginalEntityId());

        var model = resolveModelSource(ragdollId, player, captured);
        if (model == null) {
            dropState(ragdollId);
            return;
        }

        var state = STATES.get(ragdollId);
        if (state == null || state.source() != model.geoModel()) {
            float[] capturedPose = capturedAttributes(captured, model);
            var rigModel = model.rigModel() != null ? model.rigModel()
                    : new AnimatedGeoModel(model.geoModel());
            var rig = KineticRagdollRig.resolve(rigModel);
            if (rig == null) {
                // 骨架与内置 Steve/Alex 不同：不接管，交给 Ragdollified 画原版人形。
                logBailOnce("rig", "ragdoll {} uses a model whose skeleton has no KINETIC ragdoll mapping; "
                        + "falling back to the provider's humanoid body", ragdollId);
                dropState(ragdollId);
                return;
            }
            state = new KineticRagdollState(ragdollId, rig, model.geoModel(), capturedPose,
                    model.widthScale(), model.heightScale(), model.texture(), model.modelId());
            var replaced = STATES.put(ragdollId, state);
            if (replaced != null) {
                replaced.close();
            }
        } else if (model.texture() != null) {
            // 实时能力可用时始终跟随它，皮肤类模组换肤后尸体同步。
            state.texture(model.texture());
        }

        var anchor = ragdoll.getSmoothedTransform(com.raiiiden.ragdollified.RagdollPart.TORSO.index);
        if (anchor == null) {
            // 连躯干位姿都没有就无法定位模型；放弃接管，让原版人形渲染兜底。
            logBailOnce("anchor", "ragdoll {} has no torso transform yet; "
                    + "falling back to the provider's humanoid body", ragdollId);
            dropState(ragdollId);
            return;
        }
        // 位姿缓冲由状态自己持有：缺失的部位保留的是这具布娃娃自己的上一帧，
        // 而不是渲染器里共享静态数组时"上一具被处理的布娃娃"的值。
        var positions = state.partPositions();
        var rotations = state.partRotations();
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            var transform = ragdoll.getSmoothedTransform(part);
            if (transform == null) {
                // 断肢等情况下该部位可能没有位姿：保留上一帧的值，不要因此整帧不画。
                continue;
            }
            positions[part].set(transform.position.x, transform.position.y, transform.position.z);
            rotations[part].set(transform.rotation.x, transform.rotation.y,
                    transform.rotation.z, transform.rotation.w);
            if (rotations[part].lengthSquared() > 1.0e-8f) {
                rotations[part].normalize();
            } else {
                rotations[part].identity();
            }
        }

        state.ensureMacroOffsets(rotations);
        boolean settled = state.isAsleep() && state.poseUnchanged(positions, rotations);
        if (!settled) {
            if (subBonePhysics()) {
                state.step(positions, rotations, deltaSeconds,
                        groundHeightAt(anchor.position.x, anchor.position.z));
            }
            state.buildPose(positions, rotations);
            boolean extracted;
            try {
                extracted = state.extract();
            } catch (RuntimeException failure) {
                // 原生模型已被释放（例如模型资源被回收）：同一份快照不会再变好，丢掉它避免每帧重试。
                Kinetic.LOGGER.info("[Ragdollified] the model of ragdoll {} was released while its "
                        + "corpse was alive; falling back to the provider's humanoid body", ragdollId);
                CAPTURED.remove(ragdoll.getOriginalEntityId());
                dropState(ragdollId);
                return;
            }
            if (!extracted) {
                // 提取失败时不能继续接管：否则身体被跳过又没有替代模型，布娃娃会直接消失。
                logBailOnce("extract", "the native extract for ragdoll {} failed; "
                        + "falling back to the provider's humanoid body", ragdollId);
                dropState(ragdollId);
                return;
            }
            state.readJointPositions();
        }

        var texture = model.texture() != null ? model.texture() : state.texture();
        if (texture == null) {
            // 所有贴图来源都不可用：继续接管会画出一具没有贴图的身体，因此只能让位。
            logBailOnce("texture", "no texture is available for ragdoll {}; "
                    + "falling back to the provider's humanoid body", ragdollId);
            dropState(ragdollId);
            return;
        }
        draw(state, ragdollId, texture, poseStack, buffers, camera, torso);
    }

    /**
     * 本帧用于绘制的模型来源。
     *
     * <p>优先实时能力（皮肤、换模都会立刻反映）；能力不可用时退回死亡瞬间的快照——客户端玩家实体
     * 死亡 20 tick 后会被移除并触发 KINETIC 的能力重置，此时只有快照还能画出尸体。两者都没有时返回
     * {@code null}，由调用方让位给提供方的原版人形。
     */
    @Nullable
    private static ModelSource resolveModelSource(int ragdollId, @Nullable AbstractClientPlayer player,
                                                  @Nullable CapturedPose captured) {
        var cap = player == null
                ? null
                : player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (cap != null && cap.isInitializedAndEnabled() && cap.hasRenderableModel()) {
            try {
                var source = cap.getKineticGeoModel();
                var model = cap.getLoadedGeoModel();
                if (source != null && model != null) {
                    return new ModelSource(source, model, cap.getWidthScale(), cap.getHeightScale(),
                            resolveTexture(player, cap), cap.getModelId());
                }
            } catch (RuntimeException failure) {
                // 能力正处于半重置状态（例如玩家实体刚被移除）：不视为致命，改用死亡瞬间的快照。
                Kinetic.LOGGER.debug("[Ragdollified] the live KINETIC model of ragdoll {} is not "
                        + "usable right now; using the captured death pose instead", ragdollId, failure);
            }
        }
        if (captured != null) {
            // 快照自带 GeoModel；骨架映射只在建立状态时按需重建（见 ModelSource#rigModel）。
            return new ModelSource(captured.source(), null,
                    captured.widthScale(), captured.heightScale(), captured.texture(), captured.modelId());
        }
        if (player == null) {
            logBailOnce("player", "ragdoll {} has no matching client player and no captured death pose; "
                    + "falling back to the provider's humanoid body", ragdollId);
        } else {
            logBailOnce("model", "player {} has no renderable KINETIC model for ragdoll {}; "
                    + "falling back to the provider's humanoid body",
                    player.getName().getString(), ragdollId);
        }
        return null;
    }

    /** 取死亡瞬间捕获的姿态；没有捕获（例如服务器直接生成布娃娃）时退回当前姿态，仍然不会跳变。 */
    private static float[] capturedAttributes(@Nullable CapturedPose captured, ModelSource model) {
        if (captured != null && captured.source() == model.geoModel()) {
            return captured.attributes();
        }
        var rigModel = model.rigModel();
        if (rigModel != null) {
            return rigModel.getBoneAttributes();
        }
        // 不可达：快照路径的 ModelSource 一定与 captured 同源。
        return captured != null ? captured.attributes() : new float[0];
    }

    /** 降级原因只记一次；用户日志里出现哪一条，就说明接管为什么让位。 */
    private static void logBailOnce(String reason, String message, Object... arguments) {
        // SLF4J 只有在「可变参数本身就是一个数组」时才会逐项代入，因此把 reason 并进同一个数组。
        Object[] parameters = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        parameters[arguments.length] = reason;
        if (LOGGED_BAILS.add(reason)) {
            Kinetic.LOGGER.info("[Ragdollified] " + message
                    + " (reason '{}'; further occurrences are logged only at debug level)", parameters);
        } else {
            Kinetic.LOGGER.debug("[Ragdollified] " + message + " (reason '{}')", parameters);
        }
    }

    /**
     * 尸体所在位置的地表高度，供子骨骼的地面防穿透使用。
     *
     * <p>拿不到世界信息时返回 {@code NaN}，此时状态侧会整体跳过地面约束——宁可不管，也不要
     * 用一个猜出来的平面去掰关节。
     */
    private static float groundHeightAt(float x, float z) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return Float.NaN;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(x), Mth.floor(z));
    }

    /**
     * 取与实时渲染一致的贴图。
     *
     * <p>内置 Steve/Alex 模型自带固定贴图，玩家皮肤是通过 {@code SpecialPlayerRenderEvent} 换上去的；
     * 皮肤类模组（如 Quick Skin）也挂在这条链上。这里复用同一个事件，布娃娃才会穿玩家的皮肤。
     *
     * <p>事件被取消或没有给出覆盖值时退回能力自身的贴图，而不是判定为「没有贴图」：取消只表示
     * 本次替换绘制不被接受，不代表模型没有可用的贴图。内置 Steve/Alex 模型的能力贴图就是模组自带的
     * 默认皮肤（见 {@link DefaultPlayerSkins}），所以"玩家没有可用皮肤"与"退回能力贴图"是同一件事。
     */
    @Nullable
    private static ResourceLocation resolveTexture(AbstractClientPlayer player,
                                                   com.kinetic.capability.PlayerAnimatableCapability cap) {
        ResourceLocation override = null;
        try {
            var event = new SpecialPlayerRenderEvent(player, cap, cap.getModelId());
            if (!MinecraftForge.EVENT_BUS.post(event)) {
                override = event.getTextureLocationOverride();
            }
        } catch (RuntimeException failure) {
            Kinetic.LOGGER.debug("[Ragdollified] the skin chain failed for {}; "
                    + "using the capability texture instead", player.getName().getString(), failure);
        }
        // 只用"确实可用"的玩家皮肤：离线账号拿到的按 UUID 推断的默认皮肤不算玩家的皮肤，
        // 让它留在这一档会让尸体穿原版贴图、活人穿模组默认皮肤，两边不一致。
        return chooseTexture(override, isBuiltinPlayerModel(cap.getModelId()),
                DefaultPlayerSkins.loadedSkinOf(player), capabilityTexture(cap));
    }

    /**
     * 尸体贴图的优先级：皮肤链覆盖值 → 内置玩家模型的玩家皮肤 → 能力自身贴图。
     *
     * <p>中间那一档是必需的：客户端玩家实体被移除后只剩快照可用，而快照记的是玩家自己的皮肤。
     * 传入的 {@code playerSkin} 必须是"确实可用"的皮肤，不能用按 UUID 推断的占位贴图。
     */
    @Nullable
    static ResourceLocation chooseTexture(@Nullable ResourceLocation skinChainOverride,
                                          boolean builtinPlayerModel,
                                          @Nullable ResourceLocation playerSkin,
                                          @Nullable ResourceLocation capabilityTexture) {
        if (skinChainOverride != null) {
            return skinChainOverride;
        }
        if (builtinPlayerModel && playerSkin != null) {
            return playerSkin;
        }
        return capabilityTexture;
    }

    /** 宽/细手臂的内置玩家模型；它们的贴图由皮肤链提供，能力自身只有 KINETIC 资源贴图。 */
    static boolean isBuiltinPlayerModel(@Nullable String modelId) {
        return com.kinetic.model.catalog.builtin.BuiltinModelIndex.isBuiltinPlayerModel(modelId);
    }

    private static void draw(KineticRagdollState state, int ragdollId, ResourceLocation texture,
                             PoseStack poseStack, MultiBufferSource buffers,
                             net.minecraft.world.phys.Vec3 camera,
                             RagdollTransform torso) {
        var rotation = state.partRotations()[com.raiiiden.ragdollified.RagdollPart.TORSO.index];

        poseStack.pushPose();
        try {
            // 模型根骨骼枢轴是髋部而非躯干中心，差半个躯干高；取错会让整个模型上下错位。
            float hipsX = state.hipsX();
            float hipsY = state.hipsY();
            float hipsZ = state.hipsZ();
            poseStack.translate(hipsX - camera.x, hipsY - camera.y, hipsZ - camera.z);
            poseStack.mulPose(rotation);
            poseStack.scale(state.widthScale(), state.heightScale(), state.widthScale());
            poseStack.translate(-state.rootPivotX() / 16f, -state.rootPivotY() / 16f,
                    -state.rootPivotZ() / 16f);

            var renderState = state.renderState();
            // 布娃娃与玩家模型一样由封闭方盒拼成，背面裁剪只省片元（见 ClientConfig.CULL_BACK_FACES）。
            // 唯一的例外：本地相机正处在这具尸体的脑袋里时强制裁剪。提供方的绘制契约同样在这里
            // 丢掉头部（{@code ClientRagdollCamera#isHeadHidden}）；KINETIC 整具尸体只有一次原生提交，
            // 分不出「只画身体」，但强制裁剪后头骨内壁是背面、直接被剔掉，效果与丢掉头部一致：
            // 看得见肢体，看不见自己的颅内。
            boolean insideHead = ClientRagdollCamera.isHeadHidden(ragdollId, torso);
            var renderType = IGeoRenderer.entityRenderType(texture,
                    renderState.hasTranslucentVertices(),
                    insideHead || ClientConfig.CULL_BACK_FACES.get());
            int packedLight = LevelRenderer.getLightColor(Minecraft.getInstance().level,
                    BlockPos.containing(hipsX, hipsY, hipsZ));

            NativeRenderer.render(buffers.getBuffer(renderType), poseStack.last(),
                    renderState.getNativeState(), renderState.getVertexCount(),
                    packedLight, OverlayTexture.NO_OVERLAY, Color.WHITE.getColor(),
                    RenderContextType.LEVEL);

            // 尸体保留死亡时的受伤外观：血量归零，所以按最高档画。
            if (WoundFeature.enabled() && WoundFeature.allowsModel(state.modelId())) {
                WoundRenderer.render(buffers, poseStack, renderState, RenderContextType.LEVEL, renderType,
                        packedLight, OverlayTexture.NO_OVERLAY, WoundFeature.tiers(),
                        WoundFeature.configuredTint());
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 供真实相机绑定使用：这具布娃娃当前的「模型局部 → 世界」矩阵、法线矩阵与原生骨骼姿态。
     *
     * <p><b>为什么死亡后要把绑定切到布娃娃</b>：
     * <ul>
     *   <li>玩家模型在布娃娃构造完成后会被提供方从渲染里摘掉，真实相机若继续绑玩家，捕获不到任何
     *       几何，连续失败若干帧后它会把自己整个关掉——死亡视角随之失去绑定，还会弹一条失败提示；</li>
     *   <li>切到布娃娃之后相机跟着尸体走：即使把提供方自带的死亡相机关掉，也仍然有一个由模型真实
     *       眼睛位置驱动的死亡视角；两者同时开启时，提供方在自己的事件里最后写位置，仍以它为准。</li>
     * </ul>
     *
     * <p>矩阵与 {@link #draw} 用的是同一条链（髋部平移 → 躯干旋转 → 模型缩放 → 根枢轴回退），
     * 因此绑定位置与画出来的尸体严格重合。返回的 {@code originOffset} 是真实相机在
     * {@code Camera#setup} 里会加回去的「相机实体插值位置」，必须先减掉。
     */
    @Nullable
    public static RealCameraBindSource.Source bindSource(Player player, float partialTick) {
        if (!realCameraDeathBindingEnabled()) {
            return null;
        }
        var state = STATES.get(player.getId());
        if (state == null) {
            return null;
        }
        var renderState = state.renderState();
        if (renderState == null || !renderState.isValid()) {
            return null;
        }
        var geoModel = renderState.getModel();
        if (geoModel == null || geoModel.bakedModel() == null) {
            return null;
        }
        var bones = renderState.getNativeState().getBonePoses();
        if (bones == null || bones.getBoneCount() <= 0) {
            return null;
        }
        var texture = state.texture();
        if (texture == null) {
            return null;
        }

        var torso = state.partRotations()[com.raiiiden.ragdollified.RagdollPart.TORSO.index];
        var modelToWorld = new Matrix4f()
                .translation(state.hipsX(), state.hipsY(), state.hipsZ())
                .rotate(torso)
                .scale(state.widthScale(), state.heightScale(), state.widthScale())
                .translate(-state.rootPivotX() / 16f, -state.rootPivotY() / 16f,
                        -state.rootPivotZ() / 16f);
        var normalToWorld = new Matrix3f(modelToWorld).invert().transpose();
        return new RealCameraBindSource.Source(RealCameraBindSource.solverFor(geoModel), bones,
                modelToWorld, normalToWorld, texture, state.modelId(),
                interpolatedEntityPosition(player, partialTick));
    }

    /**
     * 真实相机在 {@code Camera#setup} 里给绑定位置加上的那个「相机实体插值位置」。
     *
     * <p>必须与它逐字一致，否则布娃娃路径算出来的世界位置会差一个 tick 的位移。
     */
    private static net.minecraft.world.phys.Vec3 interpolatedEntityPosition(Player player, float partialTick) {
        var position = player.position();
        return position.add(position.subtract(player.xOld, player.yOld, player.zOld)
                .scale(player.tickCount == 0 ? 0 : partialTick - 1));
    }

    private static boolean realCameraDeathBindingEnabled() {
        try {
            return ClientConfig.REAL_CAMERA_DEATH_RAGDOLL.get();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static void pruneStates() {
        if (STATES.isEmpty()) {
            return;
        }
        java.util.List<Integer> gone = null;
        for (int id : STATES.keySet()) {
            if (ClientRagdollManager.get(id) == null) {
                if (gone == null) {
                    gone = new java.util.ArrayList<>();
                }
                gone.add(id);
            }
        }
        if (gone != null) {
            for (int id : gone) {
                dropState(id);
            }
        }
    }

    /** 丢弃状态时必须释放原生模型状态，否则每个布娃娃都会漏一份 native 资源。 */
    private static void dropState(int ragdollId) {
        var removed = STATES.remove(ragdollId);
        if (removed != null) {
            removed.close();
        }
    }

    private static void clearStates() {
        if (STATES.isEmpty()) {
            return;
        }
        for (var state : STATES.values()) {
            state.close();
        }
        STATES.clear();
    }

    /** 清理长时间未消费的捕获姿态；仍被某具布娃娃使用的快照保留。 */
    public static void pruneCaptures() {
        if (CAPTURED.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttl = captureTtlMs();
        CAPTURED.entrySet().removeIf(entry -> now - entry.getValue().capturedAtMs() > ttl
                && !STATES.containsKey(entry.getKey()));
    }

    @Nullable
    private static AbstractClientPlayer findPlayer(@Nullable java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (AbstractClientPlayer player : level.players()) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    private static boolean isEnabled() {
        try {
            return ClientConfig.RAGDOLL_KINETIC_BODY.get();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean subBonePhysics() {
        try {
            return ClientConfig.RAGDOLL_SUB_BONE_PHYSICS.get();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /**
     * 布娃娃绘制距离：直接跟随 Ragdollified 的客户端配置，KINETIC 不再自己配一个。
     *
     * <p>两边各有一个距离时，只要不相等就会在同一具尸体上打架：KINETIC 更近，就会在自己的距离外
     * 把尸体交回原版人形（模型跳变）；KINETIC 更远，就会在提供方已经停止绘制之后继续画（隐形尸体）。
     * 现在只有一个权威。
     */
    private static double renderDistance() {
        return RagdollTuning.renderDistance(RagdollifiedCompat.getRagdollRenderDistance());
    }

    /**
     * 捕获快照的保留时长：跟随提供方的布娃娃存活时间。
     *
     * <p>快照只有在对应布娃娃还存在时才有用。提供方把布娃娃清掉之后，快照继续留着只是白占内存
     * （每个玩家一份模型引用加一份骨骼数组）。所以保留时间由对方的配置决定，而不是 KINETIC 自己定一个。
     */
    private static long captureTtlMs() {
        return RagdollTuning.captureTtlMs(RagdollifiedCompat.getRagdollLifetimeTicks());
    }
}
