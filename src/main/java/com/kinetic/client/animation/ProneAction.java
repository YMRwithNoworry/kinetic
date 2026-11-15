package com.kinetic.client.animation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匍匐（趴下）动作的侧躺状态。
 *
 * <p>问题：趴下时打药 / 换弹 / 手雷蓄力沿用站姿动画，而站姿动画的 {@code Head} 是"相对站直的身体"
 * 写的——它会把 {@code climbing} 里那句抵消躺姿的 {@code -90} 顶掉，头就顺着躺姿扎进地里；
 * 手臂的"向前"是胸腔法线，趴下时该法线朝地，于是双手也插进地面。
 *
 * <p>解法：先平滑侧滚到侧躺（{@code roll} 0→1），动作在侧躺姿态里做完，结束后再滚回去。
 * 本类只维护这一个标量，以及"这一轮是什么动作"（用来选同一段动画做回滚，否则回滚会换姿势）。
 * 姿态本身全部在动画里：每个被改写的通道都写成
 * {@code bone_rot('X').ax * (1 - kinetic.prone_roll) + <目标> * kinetic.prone_roll}，
 * 因此 {@code roll == 0} 时逐位等于底层动画的输出（不是"接近"），进出在任意底层姿势下都连续。
 *
 * <p>动作种类不看游戏内部状态，而是看其他频道这一帧选中的动画名（见 {@code PronePredicate}），
 * 这样对任何往那些频道塞动画的枪械联动都自动成立。
 */
public final class ProneAction {
    /** 动作种类。{@code OTHER} 覆盖吃喝这类"只需要把头和腿摆正"的动作。 */
    public enum Kind {
        NONE,
        MEDICAL,
        RELOAD,
        GRENADE,
        /** 匍匐挥砍；动画由 swing 频道播 prone:swing 或 prone:swing:alt。 */
        SWING,
        /** 匍匐投掷：动画由 swing 频道播 {@code prone:throw}，本频道只负责把它滚进侧躺。 */
        THROW,
        OTHER
    }

    /**
     * 滚入时长（秒）。
     *
     * <p>0.15s 约 3 帧：玩家在趴下状态里按出动作时，"翻过去"要短到几乎像直接切换，
     * 但又不能是单帧硬切——单帧会让躯干和手臂在同一帧内同时换值，看起来是抽搐。
     */
    static final float ENTER_SECONDS = 0.15f;
    /** 动作结束时仍趴着：滚出时长（秒），比滚入略慢，回位更放松。 */
    static final float EXIT_SECONDS = 0.24f;
    /**
     * 已经不在趴姿（站起来 / 死亡 / 睡觉 / 上车 / 开始游泳）时的滚出时长（秒）。
     *
     * <p>这时底层已经换成站姿（或死亡）动画。侧躺的混合系数就是 roll，roll→0 时每个通道都等于
     * 底层动画的值，所以"快速收掉"不会产生跳变，只会有几帧的过渡。0.12s 实测仍能看出"站着
     * 还在打针"，压到 0.04s（约 2~3 帧）后肉眼就是立刻恢复。
     */
    static final float SNAP_SECONDS = 0.08f;
    /**
     * 动作信号短暂丢失的容忍（秒）。
     *
     * <p>换弹的开火动画是一次性的：TaCZ 的重载计数还没走完，动画可能先播完一帧空档。
     * 没有这点容忍，身体会在换弹中途滚回趴姿再滚出来。
     */
    static final float DETACH_GRACE_SECONDS = 0.12f;
    /**
     * "动作早就开始了、玩家途中才趴下"时，侧躺的起始进度。
     *
     * <p>取 1.0：这一帧本身就是一次离散的姿态切换（站 → 趴），玩家要的是**趴下的同时就躺着**，
     * 不是再花 0.1 秒翻过去。从 0.7 起会留下"已经趴平了、身体却还在转"的观感，实测就是这个反馈。
     * 连续性由「每个通道都写成 {@code bone_rot*(1-roll) + 目标*roll}」保证：roll 跳变只是让
     * 这一帧直接等于侧躺目标，不存在中间帧穿地。动作**在趴下状态里**开始时不走这条路径，仍从 0 滚入。
     */
    static final float CATCH_UP_PROGRESS = 1.0f;

    /** 单帧允许推进的最大 tick 数：掉帧/暂停后不要一次跳过整段动画。 */
    static final float MAX_STEP_TICKS = 2.0f;
    /** renderTicks 是 tick，一秒 20 个。 */
    static final float TICKS_PER_SECOND = 20.0f;
    /** 小于这个 roll 视为 0，直接停止写姿态动画。 */
    public static final float EPSILON = 0.001f;

    private static final ConcurrentHashMap<Player, State> STATES = new ConcurrentHashMap<>();
    private static int observeCounter;

    private ProneAction() {
    }

    /** 玩家此刻是不是"趴着"（爬行/匍匐），排除游泳、滑铲、骑乘、死亡、睡觉、旁观。 */
    public static boolean isProne(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        return isProne(player.getPose() == Pose.SWIMMING,
                player.isSwimming(),
                player.onGround(),
                player.isDeadOrDying(),
                player.isSleeping(),
                player.isSpectator(),
                player.getVehicle() != null,
                SlideLock.isLocked(player));
    }

    /**
     * 趴姿判定本身（纯函数）。
     *
     * <p>把 {@link Player} 的取值与判定拆开，是为了让"滑铲中不侧躺""死亡/骑乘/游泳不算趴"
     * 这些规则能被离线验证——它们每一条都曾经在实机里表现为"身体摆出了不该有的姿势"。
     *
     * @param swimmingPose 姿势是否为 {@code Pose.SWIMMING}（Minecraft 把爬行与游泳/滑铲复用它）
     * @param swimming     真正在水里游
     * @param onGround     踩在地上
     * @param dead         死亡中
     * @param sleeping     睡觉
     * @param spectator    旁观
     * @param riding       骑着东西
     * @param slideLocked  {@link SlideLock} 判定为滑铲锁定中
     */
    static boolean isProne(boolean swimmingPose, boolean swimming, boolean onGround, boolean dead,
                           boolean sleeping, boolean spectator, boolean riding, boolean slideLocked) {
        return swimmingPose
                && !swimming
                && onGround
                && !dead
                && !sleeping
                && !spectator
                && !riding
                && !slideLocked;
    }

    /** {@link #isProne(Player)} 的实体版：非玩家一律 false。 */
    public static boolean isProne(@Nullable Entity entity) {
        return entity instanceof Player player && isProne(player);
    }

    /**
     * 用本帧观测推进状态并返回 roll（0..1）。
     *
     * @param renderTicks 渲染 tick（与动画求值同源，含插值），用于算帧间隔
     * @param kind        本帧识别到的动作种类；{@code NONE} 表示动作已经结束
     */
    public static float observe(@Nullable Player player, float renderTicks, Kind kind) {
        if (player == null) {
            return 0.0f;
        }
        if (player.isRemoved()) {
            STATES.remove(player);
            return 0.0f;
        }
        State state = STATES.get(player);
        if (state == null) {
            if (kind == Kind.NONE) {
                return 0.0f;
            }
            state = new State();
            state.lastRenderTicks = renderTicks;
            STATES.put(player, state);
        }
        boolean prone = isProne(player);
        float roll = advance(state, renderTicks, kind != Kind.NONE, prone, kind);
        if (roll <= 0.0f) {
            // 只有"动作也已经结束"才丢状态：站着做动作时 roll 为 0，但还记着动作种类，
            // 等再趴下要立刻接上（见 armedCatchUp）。
            if (state.kind == Kind.NONE && !state.armedCatchUp) {
                STATES.remove(player);
            }
        } else if ((++observeCounter & 0xFF) == 0) {
            prune(renderTicks);
        }
        return roll;
    }

    /**
     * 纯状态推进，供测试直接驱动（没有任何客户端依赖）。
     *
     * <p>`active` 是"动作还在进行"（换弹/打药/蓄力），`prone` 是"此刻是不是趴着"。
     * 两者分开传值，是因为四种组合的期望行为完全不同：
     * <ul>
     *   <li>站着做动作：不侧躺（0）；</li>
     *   <li>站着做动作的途中趴下：<b>当场开始侧躺</b>，动作继续（这就是"无缝衔接"）；</li>
     *   <li>趴着做动作、动作结束：慢慢回位到匍匐（EXIT）；</li>
     *   <li>趴着做动作、途中站起来：快速收掉（SNAP），别让站姿顶着一副侧躺的胳膊。</li>
     * </ul>
     *
     * @return 本帧生效的 roll（已缓动）
     */
    static float advance(State state, float renderTicks, boolean active, boolean prone, Kind kind) {
        float ticks = renderTicks - state.lastRenderTicks;
        if (!(ticks > 0.0f)) {
            ticks = 0.0f;
        }
        ticks = Math.min(ticks, MAX_STEP_TICKS);
        float dt = ticks / TICKS_PER_SECOND;
        state.lastRenderTicks = renderTicks;

        boolean actionNow = active && kind != Kind.NONE;

        if (!prone) {
            // 站着（或死亡/上车/游泳）：快速回正。roll 是每个通道的混合系数，收到 0 时通道值
            // 就等于底层动画，所以这段收尾本身是连续的；0.08s 约 5 帧，看着是"立刻直起来"，
            // 但没有硬切的生硬感。
            state.detach = 0.0f;
            // 动作仍在进行就记下来：再趴下时直接接上侧躺姿势（CATCH_UP_PROGRESS）。
            state.armedCatchUp = actionNow;
            state.progress = Math.max(0.0f, state.progress - dt / SNAP_SECONDS);
            if (state.progress <= 0.0f) {
                state.kind = Kind.NONE;
                return 0.0f;
            }
            return ease(state.progress);
        }

        if (actionNow) {
            if (state.armedCatchUp) {
                // 动作是站着时开始的：趴下这一刻直接落到侧躺，不从"站姿手臂叠躺姿"的坏姿势慢慢滚。
                state.progress = Math.max(state.progress, CATCH_UP_PROGRESS);
                state.armedCatchUp = false;
            }
            state.kind = kind;
            state.detach = DETACH_GRACE_SECONDS;
        } else if (state.detach > 0.0f) {
            // 一次性动画播完的间隙：短暂保持，避免身体抽一下再滚回来
            state.detach = Math.max(0.0f, state.detach - dt);
        }

        boolean want = actionNow || state.detach > 0.0f;
        float speed = dt / (want ? ENTER_SECONDS : EXIT_SECONDS);
        state.progress = want ? Math.min(1.0f, state.progress + speed)
                              : Math.max(0.0f, state.progress - speed);
        if (state.progress <= 0.0f) {
            state.kind = Kind.NONE;
            return 0.0f;
        }
        return ease(state.progress);
    }

    /** 缓入缓出：两端速度为 0，中间最快。 */
    static float ease(float progress) {
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        return p * p * (3.0f - 2.0f * p);
    }

    /** 当前 roll（只读，不推进）。给 molang 变量用。 */
    public static float roll(@Nullable Entity entity) {
        if (!(entity instanceof Player player)) {
            return 0.0f;
        }
        State state = STATES.get(player);
        return state == null ? 0.0f : ease(state.progress);
    }

    /** 这一轮锁定的动作种类（动作动画停了也还留着，用于回滚时选同一段动画）。 */
    public static Kind kindOf(@Nullable Player player) {
        if (player == null) {
            return Kind.NONE;
        }
        State state = STATES.get(player);
        return state == null ? Kind.NONE : state.kind;
    }

    /**
     * 开始新的一段挥动，返回这一段是否用"反手"弧线。
     *
     * <p>只在挥动起始帧调用一次（{@code swingTime == 0}）。和站姿的多段连招一样，
     * 连续挥砍左右交替；两段动画的首末帧都锚在持剑姿势上，所以交替时不会跳。
     */
    public static boolean beginSwing(@Nullable Player player, boolean advance) {
        if (player == null) {
            return false;
        }
        State state = STATES.computeIfAbsent(player, p -> new State());
        return beginSwing(state, advance);
    }

    static boolean beginSwing(State state, boolean advance) {
        if (state.swingIndex == 0) {
            state.swingIndex = 1;
        } else if (advance) {
            state.swingIndex++;
        }
        return isAlternateSwing(state);
    }

    private static boolean isAlternateSwing(State state) {
        return state.swingIndex > 0 && (state.swingIndex & 1) == 0;
    }

    /** 当前这一段是否反手（由 {@link #beginSwing} 推进）。 */
    public static boolean isAlternateSwing(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        State state = STATES.get(player);
        return state != null && isAlternateSwing(state);
    }

    public static boolean isActive(@Nullable Player player) {
        return roll(player) > EPSILON;
    }

    public static void clear(@Nullable Player player) {
        if (player != null) {
            STATES.remove(player);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static void prune(float renderTicks) {
        Iterator<Map.Entry<Player, State>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().isRemoved() || renderTicks - entry.getValue().lastRenderTicks > 200.0f) {
                iterator.remove();
            }
        }
    }

    /** 单个玩家的侧躺状态；包可见是为了让状态机可以脱离 Player 实例单独测试。 */
    static final class State {
        // 动画求值在 worker 线程池上跑，同一实体的相邻帧可能落在不同线程，
        // 因此跨帧可见性用 volatile（同一帧内的读写在同一个线程上）。
        volatile float progress;
        volatile float lastRenderTicks = -1.0f;
        volatile float detach;
        volatile boolean armedCatchUp;
        volatile Kind kind = Kind.NONE;
        /** 挥动的段数，用来左右交替（和站姿挥砍的多段连招一个道理）。 */
        volatile int swingIndex;
    }
}
