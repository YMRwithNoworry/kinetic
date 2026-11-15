package com.kinetic.client.animation.predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "站起来就停止趴下挥砍"这条规则。
 *
 * <p>控制器的挥动窗口只有 6 tick（0.3s），而侧躺挥砍弧线为 0.62s，controllers 在
 * {@code swinging} 变 false 之后仍会 CONTINUE 把动画播完。玩家在这段里站起来时，
 * 右臂会继续被趴下变体覆盖——表现就是"站着的人还在做趴下挥砍"。
 */
class SwingPredicateStopRuleTest {
    @Test
    void stopsOnlyProneVariants() {
        assertTrue(SwingPredicate.shouldStopProneVariant(false, "prone:swing"),
                "站起来时正在播 prone:swing 必须停");
        assertTrue(SwingPredicate.shouldStopProneVariant(false, "prone:throw"),
                "站起来时正在播 prone:throw 必须停");
        assertTrue(SwingPredicate.shouldStopProneVariant(false, "prone:swing:alt"),
                "站起来时正在播 prone:swing:alt 必须停");
    }

    @Test
    void leavesStandingSwingsAlone() {
        assertFalse(SwingPredicate.shouldStopProneVariant(false, "swing_hand"),
                "站姿挥砍不能被顺手停掉");
        assertFalse(SwingPredicate.shouldStopProneVariant(false, "swing:sword"));
        assertFalse(SwingPredicate.shouldStopProneVariant(false, null), "没有动画名时不动");
    }

    @Test
    void keepsPlayingWhileStillProne() {
        assertFalse(SwingPredicate.shouldStopProneVariant(true, "prone:swing"),
                "还趴着时要让 0.62s 的弧线播完，不能砍断");
        assertFalse(SwingPredicate.shouldStopProneVariant(true, "prone:swing:alt"));
        assertFalse(SwingPredicate.shouldStopProneVariant(true, "prone:throw"));
    }
}
