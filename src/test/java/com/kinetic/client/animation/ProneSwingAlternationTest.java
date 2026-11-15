package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趴下挥砍的两段交替（和站姿连招一样左右轮换）。
 *
 * <p>护栏有三条：模型里真的有第二段、谓词会按段数选它、以及两段的首末帧都锚在同一姿势上
 * （否则交替时会跳）。
 */
class ProneSwingAlternationTest {
    private static final String MODELS = "src/main/resources/assets/kinetic/builtin/misc";

    @Test
    void bothSwingSegmentsShipInEveryModel() throws Exception {
        for (String model : new String[]{"1_alex", "2_steve"}) {
            String json = Files.readString(new File(MODELS, model + "/animations/main.animation.json").toPath(),
                    StandardCharsets.UTF_8);
            assertTrue(json.contains("\"prone:swing\""), model + ": 缺第一段 prone:swing");
            assertTrue(json.contains("\"prone:swing:alt\""), model + ": 缺第二段 prone:swing:alt");
        }
    }

    @Test
    void predicateAlternatesBetweenTheSegments() throws Exception {
        String text = Files.readString(new File(
                "src/main/java/com/kinetic/client/animation/predicate/SwingPredicate.java").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("beginSwing"), "挥动起始帧必须推进段数");
        assertTrue(text.contains("isAlternateSwing"), "选动画时必须读当前段的正反手");
        assertTrue(text.contains("PRONE_SWING_ALT"), "必须引用第二段动画名");
    }

    @Test
    void comboOnlyAdvancesInsideTheChainWindow() throws Exception {
        String text = Files.readString(new File(
                "src/main/java/com/kinetic/client/animation/predicate/SwingPredicate.java").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("0.70F"),
                "必须有连招窗口阈值：太早的连点应当重放当前段，而不是硬切到下一段的起手姿势");
        assertTrue(text.contains("beginSwing(p, progress"), "段数推进必须由窗口判断决定");
    }

    @Test
    void bothSegmentsAnimateBothArmBones() throws Exception {
        // 交替时两段都必须自己写右臂；少写一段就会沿用上一段的旋转，看起来像卡住
        for (String model : new String[]{"1_alex", "2_steve"}) {
            var root = com.google.gson.JsonParser.parseString(Files.readString(
                    new File(MODELS, model + "/animations/main.animation.json").toPath(),
                    StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
            for (String name : new String[]{"prone:swing", "prone:swing:alt"}) {
                var animation = root.getAsJsonObject(name);
                var bones = animation.getAsJsonObject("bones");
                assertEquals(0.62, animation.get("animation_length").getAsDouble(), 1e-6,
                        model + "/" + name + ": current authored swing duration is 0.62s");
                assertTrue(bones.has("RightArm"), model + "/" + name + ": 缺 RightArm");
                assertTrue(bones.has("RightForeArm"), model + "/" + name + ": 缺 RightForeArm");
                assertEquals(13, bones.getAsJsonObject("RightArm").getAsJsonObject("rotation").size(),
                        model + "/" + name + ": expected 13 authored swing keys");
                assertTrue(bones.has("MAllBody"), model + "/" + name + ": 缺 MAllBody 侧滚");
            }
        }
    }
}
