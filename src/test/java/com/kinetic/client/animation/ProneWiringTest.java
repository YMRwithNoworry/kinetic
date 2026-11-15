package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匍匐侧躺的接线护栏。
 *
 * <p>状态机与数据契约都有自己的测试，但"频道到底有没有注册、是不是排在 use/swing 之后、
 * 谓词有没有去问状态机"这几件事只能从产物上确认。这里扫常量池 + 源码顺序，
 * 与 {@link SlideLockWiringTest} 同一类护栏。
 */
class ProneWiringTest {
    private static final String PRONE_ACTION = "com/kinetic/client/animation/ProneAction";
    private static final String PREDICATE = "com/kinetic/client/animation/predicate/PronePredicate";

    @Test
    void playerModelRegistersTheProneChannel() throws Exception {
        String pool = constantPool("com/kinetic/client/controller/collections/PlayerControllerCollection");
        // 控制器名是 String.format("player.%s", "prone") 拼出来的，常量池里只有 "prone"。
        assertTrue(pool.contains("prone"), "玩家模型必须注册 prone 频道");
        assertTrue(pool.contains(PREDICATE), "player.prone 必须用 PronePredicate 选动画");
    }

    @Test
    void proneChannelRunsAfterTheChannelsItOverrides() throws Exception {
        File source = new File("src/main/java/com/kinetic/client/controller/collections/PlayerControllerCollection.java");
        assertTrue(source.isFile(), "找不到 " + source.getAbsolutePath());
        String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
        int use = text.indexOf("simple(\"use\"");
        int swing = text.indexOf("simple(\"swing\"");
        int prone = text.indexOf("simple(\"prone\"");
        assertTrue(use > 0 && swing > 0 && prone > 0, "三个频道都必须注册");
        assertTrue(prone > use && prone > swing,
                "player.prone 必须排在 use/swing 之后：它是同一帧里最后写躯干/头/手臂的频道，"
                        + "排在前面就会被站姿动画顶回'头朝下'");
    }

    @Test
    void predicateConsultsTheStateMachineAndFollowsOtherChannels() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/predicate/PronePredicate");
        assertTrue(pool.contains(PRONE_ACTION), "PronePredicate 必须读取 ProneAction 状态");
        for (String controller : new String[]{"player.use", "player.fire", "player.hold_mainhand", "player.swing"}) {
            assertTrue(pool.contains(controller),
                    "PronePredicate 必须按 " + controller + " 当前在播的动画判断动作种类");
        }
        assertTrue(pool.contains("prone:action:idle"), "必须存在通用回退动画名");
        assertTrue(pool.contains("prone:swing"), "必须识别侧躺主挥砍来驱动 roll");
        assertTrue(pool.contains("prone:swing:alt"), "必须识别侧躺反手挥砍来驱动 roll");
        assertTrue(pool.contains("prone:throw"), "必须识别侧躺投掷来驱动 roll");
    }

    @Test
    void molangExposesTheRollVariable() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/molang/KineticBinding");
        assertTrue(pool.contains("prone_roll"), "KineticBinding 必须暴露 kinetic.prone_roll");
        assertTrue(pool.contains(PRONE_ACTION), "kinetic.prone_roll 必须由 ProneAction 提供");
    }

    @Test
    void controllersCanReportTheAnimationTheyArePlaying() throws Exception {
        String pool = constantPool("com/kinetic/geckolib3/core/controller/CodedAnimationController");
        assertTrue(pool.contains("getCurrentAnimationName"),
                "coded 控制器必须实现 getCurrentAnimationName（匍匐侧躺据此识别动作）");
    }

    @Test
    void proneSwingIsSelectedByTheSwingChannel() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/predicate/SwingPredicate");
        assertTrue(pool.contains("prone:swing"), "SwingPredicate 必须能在趴下时切到 prone:swing");
        assertTrue(pool.contains(PRONE_ACTION), "SwingPredicate 必须用 ProneAction.isProne 判断趴姿");
    }

    @Test
    void predicateReadsThePlayersUseStateInsteadOfOnlyChannelNames() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/predicate/PronePredicate");
        // 只看"频道当前在播什么"会漏掉一大类情况：趴下按右键掐雷时 hold 频道播的是
        // tac:hold:grenade（持握/瞄准），打药时 use 频道也可能被条件表接管。玩家状态才是
        // 唯一稳定的事实来源，缺了它就会出现"趴着按不出来、站起来做一次再趴下才行"。
        assertTrue(pool.contains("isUsingItem"), "动作识别必须读玩家是否正在使用物品");
        assertTrue(pool.contains("InnerClassify"),
                "医疗类消耗品必须复用 InnerClassify 的分类（与 use_mainhand:medical 同源）");
        assertTrue(pool.contains("net/minecraft/world/item/UseAnim"),
                "吃喝判定必须按 UseAnim，而不是猜动画名");
    }

    @Test
    void swingChannelStopsTheProneVariantOnceThePlayerIsNoLongerProne() throws Exception {
        // 规则本身（何时停）由 SwingPredicateStopRuleTest 做行为验证；这里只钉住"渲染路径确实
        // 走了那条规则"，避免有人把 shouldStopProneVariant 的调用删掉而测试仍然全绿。
        String pool = constantPool("com/kinetic/client/animation/predicate/SwingPredicate");
        assertTrue(pool.contains("shouldStopProneVariant"),
                "挥动结束分支必须调用 shouldStopProneVariant，否则站起来的人会继续把趴下弧线演完");
        assertTrue(pool.contains("prone:swing"), "停止条件必须认得趴下挥砍");
    }
    private static String constantPool(String className) throws Exception {
        var resource = "/" + className + ".class";
        try (var in = ProneWiringTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
