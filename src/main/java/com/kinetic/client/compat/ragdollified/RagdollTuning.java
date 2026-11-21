package com.kinetic.client.compat.ragdollified;

import java.util.function.Supplier;

/**
 * 布娃娃的绘制距离与快照保留时长：两者都由 Ragdollified 的配置决定，这里只做兜底换算。
 *
 * <p><b>为什么单独一个类</b>：这两个值必须跟随提供方，而"跟随"本身包含兜底逻辑
 * （拿不到配置时用什么、tick 怎么换算成毫秒）。把它放在只依赖基本类型的类里，
 * 就能在没有 Ragdollified、也没有渲染环境的单测里直接断言，而 {@link KineticRagdollRenderer}
 * 因为持有提供方类型在 JUnit 里根本加载不了。
 */
public final class RagdollTuning {
    /**
     * 拿不到提供方客户端绘制距离时的兜底值。
     *
     * <p>取提供方自己文档里的默认值 128，而不是 KINETIC 原来的 64：KINETIC 的距离更近时，尸体在 64 格外
     * 会从 KINETIC 模型跳回原版人形，而提供方那边还以为 KINETIC 在画。
     */
    public static final double DEFAULT_RENDER_DISTANCE = 128.0;

    /** 拿不到提供方布娃娃存活时间时的快照保留时长。 */
    public static final long CAPTURE_TTL_FALLBACK_MS = 60_000L;

    /** 在提供方的存活时间之上多留一点，覆盖最后一帧的收尾。 */
    public static final long CAPTURE_TTL_MARGIN_MS = 5_000L;

    private RagdollTuning() {
    }

    /** Reads numeric values exposed either by ForgeConfigSpec or Ragdollified 1.2.2 ConfigSpec. */
    static Number readConfigNumber(Object configValue) {
        if (!(configValue instanceof Supplier<?> supplier)) {
            return null;
        }
        try {
            Object value = supplier.get();
            return value instanceof Number number ? number : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 布娃娃绘制距离。
     *
     * @param fromProvider 提供方配置里的值；{@code <= 0} 或 {@code NaN} 表示拿不到
     */
    public static double renderDistance(double fromProvider) {
        if (!(fromProvider > 0.0) || Double.isInfinite(fromProvider)) {
            return DEFAULT_RENDER_DISTANCE;
        }
        return fromProvider;
    }

    /**
     * 死亡姿态快照的保留时长（毫秒）。
     *
     * <p>快照只有在对应布娃娃还存在时才有用。提供方把布娃娃清掉之后，快照继续留着只是白占内存
     * （每个玩家一份模型引用加一份骨骼数组），所以保留时间由对方的存活时间决定。
     *
     * @param lifetimeTicks 提供方的 {@code RAGDOLL_LIFETIME}；{@code <= 0} 表示拿不到
     */
    public static long captureTtlMs(int lifetimeTicks) {
        if (lifetimeTicks <= 0) {
            return CAPTURE_TTL_FALLBACK_MS;
        }
        // 余量本身就保证了"存活时间再短也不会在第一帧就被清掉"，所以不需要额外下限。
        return lifetimeTicks * 50L + CAPTURE_TTL_MARGIN_MS;
    }
}
