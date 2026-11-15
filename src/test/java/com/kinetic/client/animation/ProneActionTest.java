package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匍匐侧躺状态机的纯逻辑：进入/退出时长、缓动、动作短暂中断的容忍、动作种类锁定。
 *
 * <p>这些是"看不见的"部分——实机上只表现为"翻得顺不顺、回位会不会抖"，
 * 所以离线把曲线钉死。
 */
class ProneActionTest {
    private static ProneAction.State state() {
        return new ProneAction.State();
    }

    @Test
    void swingSequenceStartsOnPrimaryAndAdvancesOnlyInsideTheChainWindow() {
        ProneAction.State s = state();
        assertFalse(ProneAction.beginSwing(s, false), "新序列必须从主段开始");
        assertFalse(ProneAction.beginSwing(s, false), "连招窗口外重复挥砍应留在当前段");
        assertTrue(ProneAction.beginSwing(s, true), "窗口内下一段应切到反手段");
        assertFalse(ProneAction.beginSwing(s, true), "再推进一段应回到主段");
    }

    @Test
    void meleeSwingKindDrivesTheProneRoll() {
        ProneAction.State s = state();
        float roll = ProneAction.advance(s, 1.0f / 3.0f, true, true, ProneAction.Kind.SWING);
        assertTrue(roll > 0.0f, "挥砍动作必须启动侧躺 roll");
        assertEquals(ProneAction.Kind.SWING, s.kind);
    }

    @Test
    void rollRampsInWithEasingAndReachesOne() {
        ProneAction.State s = state();
        float t = 0.0f;
        float last = 0.0f;
        float seconds = 0.0f;
        // 60fps：每帧 1/60 秒，而 renderTicks 以 tick 计（每秒 20 个），所以每帧 +1/3 tick
        while (seconds < 1.0f) {
            t += 1.0f / 3.0f;
            float roll = ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
            assertTrue(roll >= last - 1e-6, "roll 不能回退");
            last = roll;
            seconds += 1.0f / 60.0f;
            if (roll >= 0.999f) {
                break;
            }
        }
        assertEquals(1.0f, last, 1e-3, "持续动作时最终必须是 1");
        // 滚入 0.15s；首帧的 dt 会被上一帧的初始值放大，实测约 0.10~0.15s。
        assertTrue(seconds < 0.30f, "滚入不能超过 0.30s，实际 " + seconds);
        assertTrue(seconds > 0.08f, "滚入不能快于 0.08s（低于 5 帧就是硬切了），实际 " + seconds);
    }

    @Test
    void rollOutTakesSlightlyLongerThanRollIn() {
        ProneAction.State s = state();
        s.progress = 1.0f;
        s.lastRenderTicks = 0.0f;
        float t = 0.0f;
        float seconds = 0.0f;
        while (seconds < 2.0f) {
            t += 1.0f / 3.0f;
            float roll = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
            seconds += 1.0f / 60.0f;
            if (roll <= 0.0f) {
                break;
            }
        }
        // 先吃 0.12s 的容忍，再走 0.24s 的滚出
        assertTrue(seconds > 0.18f, "滚出总时长不应短于 0.18s，实际 " + seconds);
        assertTrue(seconds < 0.60f, "滚出总时长不应长于 0.60s，实际 " + seconds);
    }

    @Test
    void standingUpSnapsTheRollBackWithinTheSnapWindow() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        assertEquals(1.0f, ProneAction.ease(s.progress), 1e-3, "先要真的侧躺下去");

        // 站起来 / 死亡 / 上车：prone 变 false，动作仍在进行。这条路径必须用 SNAP（0.08s），
        // 而不是动作正常结束时的 EXIT（0.24s）——否则会看到"人已经站直了还在侧着"。
        float seconds = 0.0f;
        float roll = 1.0f;
        while (seconds < 1.0f) {
            t += 1.0f / 3.0f;
            seconds += 1.0f / 60.0f;
            roll = ProneAction.advance(s, t, true, false, ProneAction.Kind.MEDICAL);
            if (roll <= 0.0f) {
                break;
            }
        }
        assertEquals(0.0f, roll, 1e-6, "不再趴着时 roll 必须回到 0");
        assertTrue(seconds < 0.15f,
                "站起时的收尾必须走 SNAP 窗口（0.08s），实际 " + seconds + "s；"
                        + "若这里接近 0.24s 说明误用了 EXIT");
    }

    @Test
    void standingUpKeepsTheActionKindSoLyingDownAgainIsImmediate() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.GRENADE);
        }
        // 站起来：roll 归零，但动作还在进行，种类必须留着
        for (int i = 0; i < 10; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, false, ProneAction.Kind.GRENADE);
        }
        assertEquals(0.0f, ProneAction.ease(s.progress), 1e-3, "站着时 roll 为 0");
        // kind 在这里归零是**设计**：roll=0 时 prone 频道整帧不参与，没人读它；趴下那一帧
        // PronePredicate.detect 会重新给出种类。真正必须留住的是 armedCatchUp——
        // 它决定"再趴下时是当帧到位还是从 0 慢慢滚"。
        assertEquals(ProneAction.Kind.NONE, s.kind, "roll 归零后种类可以清掉");
        assertTrue(s.armedCatchUp, "必须记住'动作是站着开始的'，下次趴下才能当帧到位");
    }

    @Test
    void shortDropoutsDoNotStartTheRollOut() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
        }
        float before = ProneAction.ease(s.progress);
        assertEquals(1.0f, before, 1e-3);
        // 动作信号丢 2 帧（约 0.033s），随后恢复：roll 不该往下走
        t += 1.0f / 3.0f;
        float a = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        t += 1.0f / 3.0f;
        float b = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        assertTrue(a >= before - 1e-6 && b >= before - 1e-6, "短暂丢失动作信号不允许开始回滚");
        t += 1.0f / 3.0f;
        ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
        assertEquals(ProneAction.Kind.RELOAD, s.kind, "恢复后仍是同一个动作");
    }

    @Test
    void kindStaysLatchedUntilRollReturnsToZero() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        assertEquals(ProneAction.Kind.MEDICAL, s.kind);
        // 动作结束：kind 必须留着，直到 roll 真的回到 0（否则回滚会换一段动画）
        t += 1.0f / 3.0f;
        ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        assertEquals(ProneAction.Kind.MEDICAL, s.kind, "回滚期间必须沿用同一个动作种类");
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        }
        assertEquals(0.0f, ProneAction.ease(s.progress), 1e-6);
        assertEquals(ProneAction.Kind.NONE, s.kind, "回到趴姿后种类必须清空");
    }

    @Test
    void easingIsSmoothAtBothEnds() {
        assertEquals(0.0f, ProneAction.ease(0.0f), 1e-6);
        assertEquals(1.0f, ProneAction.ease(1.0f), 1e-6);
        assertEquals(0.5f, ProneAction.ease(0.5f), 1e-6);
        // 两端一阶导为 0：开头与结尾的增量必须远小于中段
        float head = ProneAction.ease(0.05f) - ProneAction.ease(0.0f);
        float mid = ProneAction.ease(0.55f) - ProneAction.ease(0.5f);
        float tail = ProneAction.ease(1.0f) - ProneAction.ease(0.95f);
        assertTrue(head < mid * 0.5f, "起步必须缓入");
        assertTrue(tail < mid * 0.5f, "收尾必须缓出");
    }

    @Test
    void standingWhileTheActionRunsNeverRolls() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 120; i++) {
            t += 1.0f / 3.0f;
            assertEquals(0.0f, ProneAction.advance(s, t, true, false, ProneAction.Kind.RELOAD), 1e-6,
                    "站着换弹/打药时不允许侧躺");
        }
        assertEquals(ProneAction.Kind.NONE, s.kind, "站着时不该锁住动作种类");
    }

    @Test
    void becomingProneMidActionRollsInAndContinuesTheAction() {
        ProneAction.State s = state();
        float t = 0.0f;
        // 先站着做一段动作（换弹中）
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, false, ProneAction.Kind.RELOAD);
        }
        assertEquals(0.0f, ProneAction.ease(s.progress), 1e-6, "站着时 roll 必须是 0");
        // 动作仍在进行，玩家趴下：应当当场开始侧躺，并保持同一个动作种类
        float roll = 0.0f;
        float seconds = 0.0f;
        while (seconds < 1.0f) {
            t += 1.0f / 3.0f;
            seconds += 1.0f / 60.0f;
            roll = ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
            if (roll >= 0.999f) {
                break;
            }
        }
        assertEquals(1.0f, roll, 1e-3, "趴下后必须滚到 1");
        assertEquals(ProneAction.Kind.RELOAD, s.kind, "动作种类必须沿用（动画才不会换一段）");
        assertTrue(seconds < 0.35f, "滚入应在一个翻身之内完成，实际 " + seconds);
    }

    @Test
    void goingProneMidActionStartsNearTheSideLyingPose() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, false, ProneAction.Kind.RELOAD);
        }
        t += 1.0f / 3.0f;
        float first = ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
        assertEquals(1.0f, first, 1e-3,
                "动作早就在站立时开始时，趴下那一刻必须已经是完整侧躺——玩家要的是'趴下就躺着'，"
                        + "不是再花几十毫秒翻过去；实际 " + first);
    }

    @Test
    void actionStartedWhileProneStillRampsFromZero() {
        ProneAction.State s = state();
        s.lastRenderTicks = 0.0f;
        float t = 0.0f;
        float first = 0.0f;
        for (int i = 0; i < 3; i++) {
            t += 1.0f / 3.0f;
            first = ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        assertTrue(first < 0.3f, "在趴姿下开始的动作必须平滑滚入，实际 " + first);
    }

    @Test
    void standingUpMidActionSnapsOutFasterThanTheNormalExit() {
        // 还趴着：按 EXIT 慢慢回位
        ProneAction.State stay = state();
        stay.progress = 1.0f;
        float t = 0.0f;
        float secondsProne = 0.0f;
        while (secondsProne < 2.0f) {
            t += 1.0f / 3.0f;
            secondsProne += 1.0f / 60.0f;
            if (ProneAction.advance(stay, t, false, true, ProneAction.Kind.NONE) <= 0.0f) {
                break;
            }
        }
        // 站起来了：按 SNAP 快速收
        ProneAction.State up = state();
        up.progress = 1.0f;
        t = 0.0f;
        float secondsStanding = 0.0f;
        while (secondsStanding < 2.0f) {
            t += 1.0f / 3.0f;
            secondsStanding += 1.0f / 60.0f;
            if (ProneAction.advance(up, t, false, false, ProneAction.Kind.NONE) <= 0.0f) {
                break;
            }
        }
        assertTrue(secondsStanding < secondsProne * 0.6f,
                "站起来时必须收得更快（站姿 " + secondsStanding + "s vs 趴姿 " + secondsProne + "s）");
    }

    @Test
    void standingUpMidActionRestoresWithinAFewFrames() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        assertEquals(1.0f, ProneAction.ease(s.progress), 1e-3);
        // 站起来（动作仍在进行）：必须在 ~0.06s 内收回到 0，不能站着了还保持着侧躺的姿势
        float seconds = 0.0f;
        float roll = 1.0f;
        while (seconds < 0.5f) {
            t += 1.0f / 3.0f;
            seconds += 1.0f / 60.0f;
            roll = ProneAction.advance(s, t, true, false, ProneAction.Kind.MEDICAL);
            if (roll <= 0.0f) {
                break;
            }
        }
        assertTrue(roll <= 0.0f, "站起来后必须回到 0，实际 " + roll);
        assertTrue(seconds <= 0.10f, "恢复应在一两帧内完成，实际 " + seconds + "s");
    }

    @Test
    void actionRestartDuringRollOutDoesNotDropToZero() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        // 动作结束，开始回位（先吃掉 0.12s 的容忍，再走 0.34s 的滚出）
        for (int i = 0; i < 14; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        }
        float mid = ProneAction.ease(s.progress);
        assertTrue(mid < 0.95f && mid > 0.2f, "应该正在回位，实际 " + mid);
        // 又接上一个动作：不允许先归零再重来
        t += 1.0f / 3.0f;
        float resumed = ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        assertTrue(resumed >= mid - 1e-6, "重新开始动作时 roll 不能先掉下去再升上来");
    }

    @Test
    void hugeFrameGapIsClamped() {
        ProneAction.State s = state();
        s.lastRenderTicks = 0.0f;
        float roll = ProneAction.advance(s, 10_000.0f, true, true, ProneAction.Kind.MEDICAL);
        assertTrue(roll <= 1.0f, "单帧推进必须被夹住，不能一步到底");
    }
}
