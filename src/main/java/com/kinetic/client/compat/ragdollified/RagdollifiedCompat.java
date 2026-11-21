package com.kinetic.client.compat.ragdollified;

import com.kinetic.Kinetic;
import com.kinetic.client.compat.realcamera.RealCameraBindSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.LoadingModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ragdollified 兼容层：玩家死亡时改用布娃娃，并把「死亡瞬间的关节姿态」交给它。
 *
 * <p><b>可选依赖边界</b>：构建以受支持的 Ragdollified API 编译，但仅作 compile-only，不会打包提供方。
 * 运行时先检查模组是否安装；与版本相关的入口通过反射解析，失败时保留 KINETIC 自身的死亡动画。
 *
 * <p><b>用到的 API（均已对着发布版 JAR 用 javap 核实，非猜测）</b>：
 * <ul>
 *   <li>{@code RagdollifiedApi#spawn(LivingEntity)} —— 当带来源入口不可用时的无来源兼容回退，
 *       源码注释明确说明它「ignoring the automatic-death enable list」；</li>
 *   <li>{@code RagdollifiedConfig#isRagdollEnabledFor(String, boolean)} —— 玩家布娃娃是否被配置启用；</li>
 *   <li>{@code RagdollifiedConfig.RAGDOLL_LIFETIME} —— 清除时间（ticks），用于死亡姿态快照的保留时长；</li>
 *   <li>{@code MobPoseCapture#storePose(int, MobPose)} —— <b>姿态注入入口</b>；</li>
 *   <li>{@code RagdollifiedApi#hasRagdoll(Entity)} —— 判断姿态是否已被消费。</li>
 * </ul>
 *
 * <p><b>为什么需要注入姿态</b>：Ragdollified 默认通过 {@code ClientMobPoseCapture#captureNow} 读取
 * <b>实体渲染器的 vanilla 模型</b>（要求 {@code renderer instanceof LivingEntityRenderer} 且模型是
 * {@code EntityModel}）来还原死亡瞬间的姿态。KINETIC 会替换玩家渲染器与模型，导致该捕获失败并退回
 * 「作者预设的直立姿态」。这里改为 KINETIC 自己用 vanilla {@link PlayerModel} 在同一批参数下求值，
 * 再把 6 个部位的局部角度存给 Ragdollified。
 *
 * <p><b>角度约定</b>：与 {@code PoseCaptureSession#commit} 一致，存的是
 * {@code part.xRot - part.getInitialPose().xRot}（相对初始姿态的增量），而不是绝对角度。
 *
 * <p><b>为什么还要「保鲜」</b>：Ragdollified 只在姿态年龄 ≤ {@code POSE_FRESHNESS_MS}（50ms）时才
 * 直接采用它，否则会自行捕获。单人下我们在死亡事件里紧随其后写入即可；多人下它会等服务器包，
 * 那时姿态早已过期。因此死亡后会持续重写同一份姿态（见 {@link #refreshPendingDeathPoses()}），
 * 直到布娃娃真正构造出来为止。
 */
public final class RagdollifiedCompat {
    private static final String MOD_ID = "ragdollified";
    /** 排队后等多久（tick）确认布娃娃真的出现；超时就回退到 KINETIC 自己的死亡动画。 */
    static final int RAGDOLL_VERIFY_TICKS = 20;
    /** 等服务端那份布娃娃多久（tick）；等不到再自己补一个客户端布娃娃。 */
    static final int DEFERRED_SPAWN_TICKS = 6;
    private static final String API_CLASS = "com.raiiiden.ragdollified.api.RagdollifiedApi";
    private static final String CONFIG_CLASS = "com.raiiiden.ragdollified.config.RagdollifiedConfig";
    private static final String MANAGER_CLASS = "com.raiiiden.ragdollified.client.ClientRagdollManager";
    private static final String POSE_CAPTURE_CLASS = "com.raiiiden.ragdollified.MobPoseCapture";
    private static final String MOB_POSE_CLASS = "com.raiiiden.ragdollified.MobPoseCapture$MobPose";
    private static final String PART_POSE_CLASS = "com.raiiiden.ragdollified.MobPoseCapture$PartPose";
    private static final String RAGDOLL_PART_CLASS = "com.raiiiden.ragdollified.RagdollPart";
    private static final String PLAYER_ENTITY_ID = "minecraft:player";

    /** 死亡姿态最多保鲜这么久（毫秒）；超时仍未构造出布娃娃就放弃。 */
    private static final long PENDING_POSE_TTL_MS = 20_000L;

    private static boolean installed;
    private static boolean apiResolved;
    /** 已排队、等待验证布娃娃是否真的出现的实体（entityId → 截止 tick）。 */
    private static final Map<Integer, Long> PENDING_VERIFY = new ConcurrentHashMap<>();
    /** 布娃娃最终没出现的实体；它们的死亡动画不再让出。 */
    private static final Map<Integer, Long> FALLBACK_DEATHS = new ConcurrentHashMap<>();
    /** 客户端兜底生成请求（entityId → 截止 tick 与死亡伤害来源）。 */
    private static final Map<Integer, DeferredSpawn> DEFERRED_SPAWN = new ConcurrentHashMap<>();
    private static boolean poseResolved;

    @Nullable private static Method spawnMethod;
    @Nullable private static Method hasRagdollMethod;
    /**
     * {@code hasRagdoll(int)} 重载。
     *
     * <p>提供方同时提供 {@code hasRagdoll(int)} 与 {@code hasRagdoll(Entity)}。只用
     * {@code getMethod("hasRagdoll", Entity.class)} 会拿到后者，再用 {@code int} 去调用就是
     * {@code IllegalArgumentException}（被吞掉之后这个方法恒为 false），于是"姿态已被消费"的
     * 清理分支永远不会触发。所以两个重载分别解析、分别用正确的实参调用。
     */
    @Nullable private static Method hasRagdollByIdMethod;
    @Nullable private static Method isRagdollEnabledForMethod;
    @Nullable private static Object ragdollLifetimeValue;
    /**
     * {@code RagdollifiedConfig.RENDER_DISTANCE}（客户端配置）：提供方自己的布娃娃绘制距离。
     *
     * <p>KINETIC 不再自己配这个值。两边各有一个距离时，只要不相等就会在同一具尸体上打架：
     * KINETIC 的近、提供方的远，就会在 KINETIC 的距离外把尸体交回给原版人形（模型跳变）；
     * 反过来则是「提供方以为 KINETIC 在画、KINETIC 已经不管」的隐形尸体。
     */
    @Nullable private static Object ragdollRenderDistanceValue;

    /**
     * {@code ClientRagdollManager#createFromEntity(LivingEntity, DamageSource)}：唯一会把
     * 「致死一击」的方向与冲量烘进布娃娃的入口。{@code RagdollifiedApi#spawn} 走的是
     * {@code spawnFromEntity}，既没有伤害来源也没有命中信息，因此击退会丢失。
     */
    @Nullable private static Method createFromEntityMethod;
    /** {@code ClientRagdollManager#hasPendingOrActiveRagdoll(int)}：判断上面那次调用是否真的排队了。 */
    @Nullable private static Method hasPendingOrActiveMethod;
    /** 1.2.2 新增：隐藏/恢复尸体。老版本没有，解析失败即为 null。 */
    @Nullable private static Method setEntityHiddenMethod;
    @Nullable private static Method isEntityHiddenMethod;

    @Nullable private static Method storePoseMethod;
    @Nullable private static Constructor<?> mobPoseConstructor;
    @Nullable private static Constructor<?> partPoseConstructor;
    @Nullable private static Class<?> ragdollPartClass;

    /** 已捕获但尚未被消费的死亡姿态：实体 id → 姿态与其写入时刻。 */
    private static final Map<Integer, PendingPose> PENDING_POSES = new ConcurrentHashMap<>();

    private record PendingPose(Object pose, long storedAtMs) {
    }

    private record DeferredSpawn(long dueTick, @Nullable DamageSource damageSource) {
    }

    private RagdollifiedCompat() {
    }

    /**
     * 轻量存在性检测。
     *
     * <p>mixin 配置插件在启动早期就会调用它来决定是否装载兼容 Mixin，因此这里只查模组列表，
     * 不加载对方的任何类，也不注册事件监听。
     */
    public static void init() {
        // LoadingModList 在极早期（或非 Forge 环境）可能还没建立；拿不到就按"没装"处理，
        // 而不是让一次存在性检测抛 NPE。
        var modList = LoadingModList.get();
        installed = modList != null && modList.getModFileById(MOD_ID) != null;
    }

    /**
     * 运行时初始化：解析反射 API，并把布娃娃渲染接管注册到事件总线。
     *
     * <p>必须晚于 {@link #init()}，且只在提供方存在时执行；未安装时本类不会触碰任何提供方类型。
     */
    public static void initRuntime() {
        init();
        if (!installed) {
            return;
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            spawnMethod = api.getMethod("spawn", LivingEntity.class);
            hasRagdollMethod = api.getMethod("hasRagdoll", Entity.class);
            // 优先用按实体 id 的重载：调用方手里只有 id，用 Entity 重载还要反查实体。
            try {
                hasRagdollByIdMethod = api.getMethod("hasRagdoll", int.class);
            } catch (NoSuchMethodException absent) {
                // 老版本只提供 Entity 重载：下面 hasRagdoll(int) 会退回反查实体。
                hasRagdollByIdMethod = null;
            }

            Class<?> config = Class.forName(CONFIG_CLASS);
            isRagdollEnabledForMethod = config.getMethod("isRagdollEnabledFor", String.class, boolean.class);
            ragdollLifetimeValue = config.getField("RAGDOLL_LIFETIME").get(null);
            try {
                ragdollRenderDistanceValue = config.getField("RENDER_DISTANCE").get(null);
            } catch (NoSuchFieldException absent) {
                // 老版本没有客户端绘制距离：退回提供方文档里的默认值 128，而不是 KINETIC 自己的 64。
                ragdollRenderDistanceValue = null;
            }

            resolveSpawnApi();
            apiResolved = true;
            Kinetic.LOGGER.info("Ragdollified compat enabled (ragdoll lifetime: {} ticks, "
                            + "damage-source spawn: {})",
                    getRagdollLifetimeTicks(), createFromEntityMethod != null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            apiResolved = false;
            spawnMethod = null;
            hasRagdollMethod = null;
            hasRagdollByIdMethod = null;
            isRagdollEnabledForMethod = null;
            ragdollLifetimeValue = null;
            ragdollRenderDistanceValue = null;
            createFromEntityMethod = null;
            hasPendingOrActiveMethod = null;
            Kinetic.LOGGER.warn("Ragdollified is present but its API could not be resolved; "
                    + "KINETIC keeps its own death animation", failure);
        }

        if (apiResolved) {
            resolvePoseApi();
            // 用 KINETIC 模型绘制布娃娃：注册在 HIGHEST，必须先于 Ragdollified 建立本帧的接管状态。
            KineticRagdollRenderer.register(net.minecraftforge.common.MinecraftForge.EVENT_BUS);
            // 真实相机在死亡后改绑布娃娃。这里只注入一个回调：没装真实相机时它永远不会被调用；
            // 装了的时候，也仍然由 KineticRagdollRenderer 判断这一帧是否真的接管了这具尸体。
            RealCameraBindSource.setRagdollProvider(KineticRagdollRenderer::bindSource);
        }
    }

    /**
     * 解析「带伤害来源的排队入口」。
     *
     * <p>失败只影响击退（退回 {@code RagdollifiedApi#spawn}，布娃娃仍然会出现），因此不设为致命错误。
     */
    private static void resolveSpawnApi() {
        try {
            Class<?> manager = Class.forName(MANAGER_CLASS);
            createFromEntityMethod = manager.getMethod("createFromEntity", LivingEntity.class,
                    net.minecraft.world.damagesource.DamageSource.class);
            hasPendingOrActiveMethod = manager.getMethod("hasPendingOrActiveRagdoll", int.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            createFromEntityMethod = null;
            hasPendingOrActiveMethod = null;
            Kinetic.LOGGER.warn("Ragdollified does not expose its damage-source spawn entry; "
                    + "death ragdolls will lose the killing blow's knockback", failure);
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            setEntityHiddenMethod = api.getMethod("setEntityHidden", int.class, boolean.class);
            isEntityHiddenMethod = api.getMethod("isEntityHidden", int.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            // 1.2.2 才有的 API；老版本没有也能正常工作，只是回退时放不出被隐藏的实体。
            setEntityHiddenMethod = null;
            isEntityHiddenMethod = null;
            Kinetic.LOGGER.debug("Ragdollified does not expose setEntityHidden/isEntityHidden", failure);
        }
    }

    /** 解析姿态注入所需的类型；失败只影响「初始姿态」，不影响布娃娃本身。 */
    private static void resolvePoseApi() {
        try {
            Class<?> capture = Class.forName(POSE_CAPTURE_CLASS);
            Class<?> mobPose = Class.forName(MOB_POSE_CLASS);
            Class<?> partPose = Class.forName(PART_POSE_CLASS);
            ragdollPartClass = Class.forName(RAGDOLL_PART_CLASS);

            storePoseMethod = capture.getMethod("storePose", int.class, mobPose);
            mobPoseConstructor = mobPose.getConstructor(Map.class);
            partPoseConstructor = partPose.getConstructor(float.class, float.class, float.class);

            poseResolved = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            poseResolved = false;
            storePoseMethod = null;
            mobPoseConstructor = null;
            partPoseConstructor = null;
            ragdollPartClass = null;
            Kinetic.LOGGER.warn("Ragdollified pose injection is unavailable; "
                    + "ragdolls will use its authored rest pose", failure);
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** 该 API 是否可用（模组存在且反射解析成功）。 */
    public static boolean isAvailable() {
        return installed && apiResolved;
    }

    /**
     * 玩家布娃娃是否被 Ragdollified 的配置启用。
     *
     * <p>读取失败（配置尚未加载等）时返回 {@code false}，即保持 KINETIC 原有死亡动画，不做任何越权行为。
     */
    public static boolean isPlayerRagdollEnabled() {
        if (!isAvailable() || isRagdollEnabledForMethod == null) {
            return false;
        }
        try {
            Object result = isRagdollEnabledForMethod.invoke(null, PLAYER_ENTITY_ID, true);
            return result instanceof Boolean enabled && enabled;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /** 布娃娃清除时间（ticks），来自 Ragdollified 配置，用于快照保留时长与诊断。 */
    public static int getRagdollLifetimeTicks() {
        Number value = RagdollTuning.readConfigNumber(ragdollLifetimeValue);
        return value == null ? -1 : value.intValue();
    }

    /**
     * 布娃娃绘制距离（方块），来自 Ragdollified 的客户端配置。
     *
     * <p>返回负数表示拿不到（老版本没有这个字段，或配置尚未加载），调用方应退回提供方文档里的默认值。
     */
    public static double getRagdollRenderDistance() {
        Number value = RagdollTuning.readConfigNumber(ragdollRenderDistanceValue);
        return value == null ? -1.0 : value.doubleValue();
    }

    /**
     * 请求为指定实体生成布娃娃，并把「致死一击」的伤害来源一并交给提供方。
     *
     * <p>只有当 Ragdollified 的配置确实启用了玩家布娃娃时才调用：它的排队入口会忽略自动死亡白名单，
     * 无条件调用等于覆盖使用者的配置。
     *
     * <p><b>为什么必须带伤害来源</b>：提供方在构造刚体时用伤害来源解析死亡方向与命中冲量
     * （{@code RagdollSpawnState#applyAttackerDirectionFallback}、{@code RagdollHitTracker#resolveAndPlan}），
     * 并把结果烘进 {@code SpawnData}；{@code RagdollifiedApi#spawn(entity)} 走的是没有伤害来源的
     * {@code spawnFromEntity}，因此击退会整个丢失。
     *
     * @return 是否成功排队（或该实体已经有排队/生效中的布娃娃）
     */
    public static boolean spawnRagdoll(LivingEntity entity, @Nullable DamageSource damageSource) {
        if (!isAvailable() || !isPlayerRagdollEnabled()) {
            return false;
        }
        if (createFromEntityMethod != null) {
            try {
                // createFromEntity 永远返回 null（布娃娃由物理线程稍后构造），因此以「是否已排队」判断结果。
                createFromEntityMethod.invoke(null, entity, damageSource);
                if (hasPendingOrActive(entity.getId())) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Kinetic.LOGGER.warn("Failed to queue a Ragdollified ragdoll with its damage source; "
                        + "retrying without it", failure);
            }
        }
        if (spawnMethod == null) {
            return false;
        }
        try {
            Object result = spawnMethod.invoke(null, entity);
            return result instanceof Boolean queued && queued;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Kinetic.LOGGER.warn("Failed to spawn a Ragdollified ragdoll", failure);
            return false;
        }
    }

    /** 该实体是否已有排队或已构造的布娃娃。 */
    public static boolean hasPendingOrActive(int entityId) {
        if (hasPendingOrActiveMethod == null) {
            return false;
        }
        try {
            Object result = hasPendingOrActiveMethod.invoke(null, entityId);
            return result instanceof Boolean present && present;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * 客户端玩家死亡入口：注入死亡姿态，并延后安排客户端兜底，优先等待服务端权威布娃娃。
     *
     * <p><b>为什么不能只靠 {@code LivingDeathEvent}</b>：Forge 只在 {@code LivingEntity#die} 里发出该事件，
     * 而客户端的 {@code LivingEntity#hurt} 在 {@code level().isClientSide} 时直接返回，
     * {@code handleEntityEvent(3)} 又显式跳过玩家，因此玩家死亡时客户端根本不会收到这个事件。
     * 只挂在它上面的结果是：客户端通常捕获不到死亡姿态，无法及时续上姿态快照，也无法在服务端布娃娃未同步时
     * 安排带致死伤害来源的客户端兜底。
     *
     * @return 是否已请求生成布娃娃
     */
    public static boolean handleClientDeath(AbstractClientPlayer player) {
        return handleClientDeath(player, player.getLastDamageSource());
    }

    /**
     * 同上，但由调用方给出致死一击的伤害来源（{@code LivingDeathEvent#getSource()}）。
     *
     * @return 是否已捕获姿态并排入延后兜底队列
     */
    public static boolean handleClientDeath(AbstractClientPlayer player, @Nullable DamageSource source) {
        if (!isAvailable() || !isPlayerRagdollEnabled()) {
            return false;
        }
        // 顺序不能反：提供方只在姿态年龄 ≤50ms 时采用注入的姿态，否则会退回它自己捕获的预设姿态。
        boolean posed = storeDeathPose(player);
        // 1.2.2 起服务器是玩家布娃娃的权威生成方。此处绝不能立刻在客户端重复 spawn：
        // 先让服务端的死亡处理和 spawn 包有机会抵达，六 tick 后仍没有时再走客户端兜底。
        DEFERRED_SPAWN.put(player.getId(),
                new DeferredSpawn(currentTick() + DEFERRED_SPAWN_TICKS, source));
        Kinetic.LOGGER.debug("Captured Ragdollified death pose for {}; waiting {} ticks for the server ragdoll "
                        + "before client fallback (pose captured: {})",
                player.getName().getString(), DEFERRED_SPAWN_TICKS, posed);
        return true;
    }

    /**
     * 把玩家「死亡瞬间」的关节姿态交给 Ragdollified，作为布娃娃的初始姿态。
     *
     * <p>用 vanilla {@link PlayerModel} 以与 {@code LivingEntityRenderer} 相同的参数求值：肢体摆动、
     * 头部相对身体的偏航、俯仰都取死亡当刻的值，因此布娃娃不会以僵直站立的姿态出现。
     *
     * @return 是否成功注入
     */
    public static boolean storeDeathPose(LivingEntity entity) {
        if (!isAvailable() || !poseResolved || !(entity instanceof AbstractClientPlayer player)) {
            return false;
        }
        // 同一时刻把 KINETIC 当前正在绘制的骨骼姿态整份捕获下来：布娃娃的第一帧就从它出发，
        // 因此肘、膝等细分骨骼不会在变成布娃娃的瞬间被拉直。
        KineticRagdollRenderer.capturePose(player);
        try {
            Object pose = capturePose(player);
            if (pose == null) {
                return false;
            }
            storePoseMethod.invoke(null, player.getId(), pose);
            PENDING_POSES.put(player.getId(), new PendingPose(pose, System.currentTimeMillis()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Kinetic.LOGGER.warn("Failed to store the death pose for a Ragdollified ragdoll", failure);
            return false;
        }
    }

    /**
     * 保持已捕获的死亡姿态「新鲜」，直到它被消费。
     *
     * <p>Ragdollified 只采用 50ms 内的姿态。多人游戏里布娃娃由服务器包创建，到达时早已超时，
     * 于是它会退回自行捕获——而 KINETIC 替换了渲染器，那条路必然失败。这里在客户端每 tick 重写
     * 同一份姿态，把新鲜度窗口续上，确保它最终仍会采用我们的死亡姿态。
     */
    public static void refreshPendingDeathPoses() {
        if (PENDING_POSES.isEmpty() || !poseResolved || storePoseMethod == null) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            PENDING_POSES.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, PendingPose>> iterator = PENDING_POSES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingPose> entry = iterator.next();
            if (hasRagdoll(entry.getKey())) {
                // 姿态已被消费
                iterator.remove();
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity) || now - entry.getValue().storedAtMs() > PENDING_POSE_TTL_MS) {
                iterator.remove();
                continue;
            }
            try {
                storePoseMethod.invoke(null, entry.getKey(), entry.getValue().pose());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                iterator.remove();
            }
        }
    }

    private static boolean hasRagdoll(int entityId) {
        try {
            if (hasRagdollByIdMethod != null) {
                Object result = hasRagdollByIdMethod.invoke(null, entityId);
                return result instanceof Boolean present && present;
            }
            if (hasRagdollMethod != null) {
                // 退回按实体查询：只有 id 时必须先反查到实体，否则参数类型对不上。
                var level = Minecraft.getInstance().level;
                Entity entity = level == null ? null : level.getEntity(entityId);
                if (entity == null) {
                    return false;
                }
                Object result = hasRagdollMethod.invoke(null, entity);
                return result instanceof Boolean present && present;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
        return false;
    }

    /** 用 vanilla 玩家模型在「此刻」求值，产出 Ragdollified 的 {@code MobPose}。 */
    @Nullable
    private static Object capturePose(AbstractClientPlayer player) throws ReflectiveOperationException {
        boolean slim = "slim".equals(player.getModelName());
        PlayerModel<AbstractClientPlayer> model = new PlayerModel<>(
                Minecraft.getInstance().getEntityModels()
                        .bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER),
                slim);

        float limbSwing = player.walkAnimation.position();
        float limbSwingAmount = player.walkAnimation.speed();
        float netHeadYaw = Mth.wrapDegrees(player.getYHeadRot() - player.getVisualRotationYInDegrees());
        model.prepareMobModel(player, limbSwing, limbSwingAmount, 1.0f);
        model.setupAnim(player, limbSwing, limbSwingAmount, player.tickCount, netHeadYaw, player.getXRot());

        Map<Object, Object> poses = new LinkedHashMap<>(6);
        poses.put(ragdollPart("TORSO"), partPose(model.body));
        poses.put(ragdollPart("HEAD"), partPose(model.head));
        poses.put(ragdollPart("LEFT_ARM"), partPose(model.leftArm));
        poses.put(ragdollPart("RIGHT_ARM"), partPose(model.rightArm));
        poses.put(ragdollPart("LEFT_LEG"), partPose(model.leftLeg));
        poses.put(ragdollPart("RIGHT_LEG"), partPose(model.rightLeg));
        return mobPoseConstructor.newInstance(poses);
    }

    private static Object ragdollPart(String name) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object part = Enum.valueOf((Class<? extends Enum>) ragdollPartClass, name);
        return part;
    }

    /** 与 {@code PoseCaptureSession#commit} 一致：存相对初始姿态的角度增量。 */
    private static Object partPose(ModelPart part) throws ReflectiveOperationException {
        net.minecraft.client.model.geom.PartPose rest = part.getInitialPose();
        return partPoseConstructor.newInstance(
                part.xRot - rest.xRot, part.yRot - rest.yRot, part.zRot - rest.zRot);
    }

    /**
     * KINETIC 是否应当让出死亡动画。
     *
     * <p>让出条件：Ragdollified 存在、API 可用、且配置启用了玩家布娃娃。此后玩家渲染会由
     * Ragdollified 的 {@code HideDeadEntityMixin}（注入 {@code EntityRenderDispatcher#render}）
     * 在布娃娃构造完成后接管并隐藏，因此继续播放 KINETIC 死亡动画只会造成重复表现。
     */
    public static boolean shouldYieldDeathAnimation() {
        return isPlayerRagdollEnabled();
    }

    /**
     * 同上，但按实体判断：**布娃娃没有真的生成出来时，不要让出死亡动画**。
     *
     * <p>实战教训：提供方换版本后接口签名变了，{@code createFromEntity} 调不通、布娃娃没生成，
     * 而这边照样让出死亡动画——尸体就既没有布娃娃、也没有 KINETIC 的死亡姿势，直接"消失"。
     * 现在排队后会盯几个 tick，确认布娃娃真的存在；没有就回退到自己画死亡姿势。
     */
    public static boolean shouldYieldDeathAnimation(Entity entity) {
        if (entity == null) {
            return shouldYieldDeathAnimation();
        }
        int id = entity.getId();
        if (FALLBACK_DEATHS.containsKey(id)) {
            return false;
        }
        // 我们自己排过队（spawn 返回成功）——让出，由提供方接管。
        if (PENDING_VERIFY.containsKey(id)) {
            return true;
        }
        // 提供方自己生成的那条路：**只有布娃娃真的存在或在途时才让出**。
        // 1.2.2 的 ClientDeathHandler 会先用模型类型判定能不能生成布娃娃，而 KINETIC 换过渲染器，
        // 判定为不支持 → 提供方根本不生成。此时若还让出死亡动画，尸体就既没有布娃娃也没有
        // 死亡姿势——玩家看到的就是"死了直接消失"。
        return isPlayerRagdollEnabled() && hasRagdollById(id);
    }

    /**
     * 排队之后盯一段时间：布娃娃没出现就登记为回退，让死亡动画继续画。
     *
     * <p>由客户端每 tick 调用。
     */
    public static void verifyQueuedRagdolls() {
        flushDeferredSpawns();
        if (PENDING_VERIFY.isEmpty()) {
            return;
        }
        long now = currentTick();
        Iterator<Map.Entry<Integer, Long>> it = PENDING_VERIFY.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int entityId = entry.getKey();
            if (hasRagdollById(entityId)) {
                it.remove();
                continue;
            }
            if (now < entry.getValue()) {
                continue;
            }
            it.remove();
            FALLBACK_DEATHS.put(entityId, now);
            unhideEntity(entityId);
            Kinetic.LOGGER.warn("[Ragdollified] ragdoll for entity {} never appeared; "
                    + "falling back to the KINETIC death animation so the corpse stays visible",
                    entityId);
        }
    }

    /**
     * 等服务端那份布娃娃；到点还没有，才由客户端补一个。
     *
     * <p>顺序很重要：先信服务端，避免同一具尸体同时存在两份布娃娃。
     */
    private static void flushDeferredSpawns() {
        if (DEFERRED_SPAWN.isEmpty()) {
            return;
        }
        long now = currentTick();
        Iterator<Map.Entry<Integer, DeferredSpawn>> it = DEFERRED_SPAWN.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int entityId = entry.getKey();
            if (hasRagdollById(entityId)) {
                it.remove();
                continue;
            }
            DeferredSpawn deferred = entry.getValue();
            if (now < deferred.dueTick()) {
                continue;
            }
            it.remove();
            var level = Minecraft.getInstance().level;
            if (level == null) {
                continue;
            }
            var entity = level.getEntity(entityId);
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            DamageSource damageSource = deferred.damageSource() != null
                    ? deferred.damageSource() : living.getLastDamageSource();
            if (spawnRagdoll(living, damageSource)) {
                PENDING_VERIFY.put(entityId, now + RAGDOLL_VERIFY_TICKS);
                Kinetic.LOGGER.info("[Ragdollified] server did not provide a ragdoll for {} within {} ticks; "
                        + "queued a client-side one", entityId, DEFERRED_SPAWN_TICKS);
            }
        }
    }

    /** 实体离开客户端世界后清掉回退标记，避免 id 复用时误判。 */
    public static void forgetEntity(int entityId) {
        FALLBACK_DEATHS.remove(entityId);
        PENDING_VERIFY.remove(entityId);
        DEFERRED_SPAWN.remove(entityId);
    }

    /** 被登记为回退的死亡实体数（测试与诊断用）。 */
    static int fallbackCount() {
        return FALLBACK_DEATHS.size();
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /**
     * 把被提供方隐藏的实体放出来。
     *
     * <p>1.2.2 新增了 {@code setEntityHidden}/{@code isEntityHidden}：它可能先隐藏尸体再尝试生成，
     * 生成失败就再也没人把它放出来。回退时必须自己恢复可见性。
     */
    private static void unhideEntity(int entityId) {
        if (setEntityHiddenMethod == null) {
            return;
        }
        try {
            Object hidden = isEntityHiddenMethod == null ? null : isEntityHiddenMethod.invoke(null, entityId);
            if (Boolean.TRUE.equals(hidden)) {
                setEntityHiddenMethod.invoke(null, entityId, false);
                Kinetic.LOGGER.info("[Ragdollified] un-hid entity {} (its ragdoll never appeared)", entityId);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 放不出来也不影响死亡动画
        }
    }

    private static boolean hasRagdollById(int entityId) {
        if (!isAvailable()) {
            return false;
        }
        if (hasRagdollByIdMethod != null) {
            try {
                Object result = hasRagdollByIdMethod.invoke(null, entityId);
                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 接着检查本地队列；两个探测入口相互独立。
            }
        }
        if (hasPendingOrActiveMethod != null) {
            try {
                Object result = hasPendingOrActiveMethod.invoke(null, entityId);
                return Boolean.TRUE.equals(result);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 查不到就当没有：宁可回退画死亡姿势，也不要让尸体消失。
            }
        }
        return false;
    }
}
