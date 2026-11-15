package com.kinetic.client.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kinetic.client.animation.molang.CustomMolangParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 匍匐专用动画的数据契约。
 *
 * <p>这些约定一旦破掉，实机上就是"翻到一半跳一下""头又开始扎地"或者"腿被冻住"，
 * 但都能离线钉住：
 * <ul>
 *   <li>{@code prone:action:*} 的每个通道都必须带 {@code kinetic.prone_roll} 的混合——
 *       否则 roll=0 时它不再等于底层动画的输出，进出侧躺会跳变；</li>
 *   <li>它们不许写 {@code Root / AllBody / DownBody / 腿 / 手}：下肢与根由主频道独占，
 *       手由原动作动画负责；</li>
 *   <li>{@code bone_rot('X')} 里的骨骼名必须真的存在于该模型；</li>
 *   <li>{@code tac:climb*} 不许再写下肢与根——那是"开火把腿部爬行冻住"的根因。</li>
 * </ul>
 */
class PronePoseDataTest {
    private static final String[] MODELS = {"2_steve", "1_alex"};

    /** 侧躺动画禁止写的骨骼。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "Root", "DownBody",
            "LeftHand", "RightHand", "LeftHandLocator", "RightHandLocator");

    /** 用于验证 bone_rot 目标实在的骨骼集合来源：主模型几何。 */
    private static Set<String> modelBones(String model) throws Exception {
        String path = "/assets/kinetic/builtin/misc/" + model + "/models/main.json";
        JsonObject root;
        try (var in = PronePoseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺少模型: " + path);
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> names = new TreeSet<>();
        collect(root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones"), names);
        return names;
    }

    private static void collect(JsonArray bones, Set<String> out) {
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            out.add(bone.get("name").getAsString());
            if (bone.has("children")) {
                collect(bone.getAsJsonArray("children"), out);
            }
        }
    }

    private static JsonObject animations(String model, String file) throws Exception {
        String path = "/assets/kinetic/builtin/misc/" + model + "/animations/" + file;
        try (var in = PronePoseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺少动画文件: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animations");
        }
    }

    /** 收集一个动画里所有 "字符串形式" 的通道值（molang 表达式）。 */
    private static List<String> expressions(JsonObject animation) {
        List<String> out = new ArrayList<>();
        for (var bone : animation.getAsJsonObject("bones").entrySet()) {
            for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                JsonElement value = channel.getValue();
                if (value.isJsonArray()) {
                    collectStrings(value.getAsJsonArray(), out);
                } else if (value.isJsonObject()) {
                    for (var key : value.getAsJsonObject().entrySet()) {
                        JsonElement v = key.getValue();
                        if (v.isJsonArray()) {
                            collectStrings(v.getAsJsonArray(), out);
                        } else if (v.isJsonObject() && v.getAsJsonObject().has("post")) {
                            collectStrings(v.getAsJsonObject().getAsJsonArray("post"), out);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 取一个关键帧分量的"目标角度"。
     *
     * <p>绝对动画里是数字字面量；侧躺动画里是
     * {@code bone_rot('X').x*(1-kinetic.prone_roll)+(-86.0000)*kinetic.prone_roll}，
     * 取其中括号里的常量即可比较连续性。
     */
    private static double number(JsonElement element) {
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        String text = element.getAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\+\\((-?[0-9.]+)\\)\\*kinetic\\.prone_roll").matcher(text);
        assertTrue(matcher.find(), "无法从通道表达式里取出目标角度: " + text);
        return Double.parseDouble(matcher.group(1));
    }

    private static void collectStrings(JsonArray array, List<String> out) {
        for (JsonElement e : array) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                out.add(e.getAsString());
            }
        }
    }

    @Test
    void rolledAnimationsBlendEveryChannelFromTheLivePose() throws Exception {
        for (String model : MODELS) {
            var all = animations(model, "main.animation.json");
            List<String> names = new ArrayList<>();
            for (var entry : all.entrySet()) {
                if (entry.getKey().startsWith("prone:action:")) {
                    names.add(entry.getKey());
                }
            }
            assertFalse(names.isEmpty(), model + ": 缺少 prone:action:* 动画");
            for (String name : names) {
                JsonObject anim = all.getAsJsonObject(name);
                JsonObject bones = anim.getAsJsonObject("bones");
                assertTrue(bones.has("MAllBody"), model + "/" + name + ": 必须自带 MAllBody 侧滚");
                assertTrue(bones.has("AllBody"),
                        model + "/" + name + ": 必须把 AllBody 压平——TaCZ 的站姿动画在 AllBody 上写了"
                                + " head_yaw 补偿，趴下时它的局部 Y 轴就是世界侧滚轴，会把侧躺的身体再拧一次");
                for (var bone : bones.entrySet()) {
                    assertFalse(FORBIDDEN.contains(bone.getKey()),
                            model + "/" + name + ": 不允许写 " + bone.getKey());
                    String json = bone.getValue().toString();
                    assertTrue(json.contains("kinetic.prone_roll"),
                            model + "/" + name + "/" + bone.getKey()
                                    + ": 每个通道都必须用 kinetic.prone_roll 混合，否则 roll=0 时与底层动画不一致");
                }
                for (String expression : expressions(anim)) {
                    try {
                        // 与运行期同一个绑定集（kinetic / ysm / ctrl / math / query），
                        // 因此 kinetic.prone_roll、bone_rot(...) 都按真实语义解析。
                        CustomMolangParser.parseSingleExpressionUnsafe(expression);
                    } catch (Exception e) {
                        fail(model + "/" + name + ": molang 表达式解析失败: " + expression + " → " + e);
                    }
                }
            }
        }
    }

    @Test
    void boneRotTargetsExistInTheModel() throws Exception {
        for (String model : MODELS) {
            Set<String> bones = modelBones(model);
            var all = animations(model, "main.animation.json");
            for (var entry : all.entrySet()) {
                if (!entry.getKey().startsWith("prone:")) {
                    continue;
                }
                for (String expression : expressions(entry.getValue().getAsJsonObject())) {
                    int index = 0;
                    while ((index = expression.indexOf("bone_rot('", index)) >= 0) {
                        int end = expression.indexOf(')', index);
                        String bone = expression.substring(index + "bone_rot('".length(), end).replace("'", "").trim();
                        assertTrue(bones.contains(bone),
                                model + "/" + entry.getKey() + ": bone_rot 指向不存在的骨骼 " + bone);
                        index = end;
                    }
                }
            }
        }
    }

    /**
     * 挥砍与投掷都必须先侧躺。
     *
     * <p>趴平时肩膀只有 4 单位高、躯干就在旁边：站姿的弧线叠上去，手臂只能贴着身体小幅摆动
     * （"不够舒展"），投掷更是整段穿过躯干（实测右臂∩头 5.1）。侧躺 70° 才腾出挥动空间。
     */
    /**
     * 肘只能往一个方向弯：前臂通道不许穿过 0 再翻回来。
     *
     * <p>实战教训：求解器细化时没把前臂角度夹在弯折范围内，挥砍中段得到 +16、投掷中段 +12，
     * 于是前臂在一段动画里从负翻到正再翻回负——玩家看到的就是"胳膊反折"。
     *
     * <p>"穿过 0"这个判据只在**前臂只有 Z 一个轴在动**时成立。挥砍改成锚在
     * {@code hold_mainhand:sword} 之后，那一支的 X/Y 是 {@code (-108.8, -33.9)}——
     * 屈曲主要由 X/Y 承担，Z 从 +76 走到 -8 再回来时，实际肘关节一直是屈的
     * （Python 侧实测 -59°…-143°，从未过 0）。所以这里按"是否单轴"分开判：
     * 单轴动画仍然禁止穿 0，多轴动画只判量级，真实肘角由
     * {@code analysis/prone_diag.py} 的带符号铰链角守护。
     */
    @Test
    void foreArmsNeverBendBackwards() throws Exception {
        for (String model : MODELS) {
            var all = animations(model, "main.animation.json");
            for (var entry : all.entrySet()) {
                String name = entry.getKey();
                if (!name.startsWith("prone:")) {
                    continue;
                }
                JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
                for (String bone : List.of("LeftForeArm", "RightForeArm")) {
                    if (!bones.has(bone)) {
                        continue;
                    }
                    JsonElement rotation = bones.getAsJsonObject(bone).get("rotation");
                    List<double[]> values = new ArrayList<>();
                    if (rotation.isJsonArray()) {
                        JsonArray arr = rotation.getAsJsonArray();
                        values.add(new double[]{number(arr.get(0)), number(arr.get(1)), number(arr.get(2))});
                    } else {
                        for (var key : rotation.getAsJsonObject().entrySet()) {
                            JsonArray arr = key.getValue().getAsJsonObject().getAsJsonArray("post");
                            values.add(new double[]{number(arr.get(0)), number(arr.get(1)), number(arr.get(2))});
                        }
                    }
                    boolean singleAxis = values.stream()
                            .allMatch(v -> Math.abs(v[0]) <= 1.0 && Math.abs(v[1]) <= 1.0);
                    if (singleAxis) {
                        boolean hasNegative = values.stream().anyMatch(v -> v[2] < -1.0);
                        boolean hasPositive = values.stream().anyMatch(v -> v[2] > 1.0);
                        assertFalse(hasNegative && hasPositive,
                                model + "/" + name + "/" + bone + ": 前臂在动画中途翻到另一侧（"
                                        + values.stream().map(v -> v[2]).toList() + "），肘会看起来反折");
                    }
                    for (double[] v : values) {
                        assertTrue(Math.abs(v[2]) <= 165.0,
                                model + "/" + name + "/" + bone + ": 肘弯 " + v[2] + " 度超出人类活动范围");
                    }
                }
            }
        }
    }

    @Test
    void proneMeleeAndThrowRollTheBody() throws Exception {
        for (String model : MODELS) {
          for (String name : List.of("prone:swing", "prone:swing:alt", "prone:throw")) {
            JsonObject anim = animations(model, "main.animation.json").getAsJsonObject(name);
            assertNotNull(anim, model + ": 缺少 " + name);
            JsonObject bones = anim.getAsJsonObject("bones");
            // 站姿投掷是把手甩到身体前下方，趴着时就是往地里扔、并且胳膊穿过躯干。
            // 所以匍匐投掷必须像打药/换弹那样先侧躺（MAllBody 承载侧滚），手臂才有空间。
            assertTrue(bones.has("MAllBody"), model + "/" + name + ": 必须自带 MAllBody 侧滚");
            assertTrue(bones.has("AllBody"), model + "/" + name + ": 必须压平 AllBody");
            for (var bone : bones.entrySet()) {
                assertFalse(FORBIDDEN.contains(bone.getKey()),
                        model + "/" + name + ": 不允许写 " + bone.getKey());
                assertTrue(bone.getValue().toString().contains("kinetic.prone_roll"),
                        model + "/" + name + "/" + bone.getKey() + ": 必须用 kinetic.prone_roll 混合");
            }
          }
        }
    }

    @Test
    void proneMeleeAndThrowKeepTheLegsFree() throws Exception {
        for (String model : MODELS) {
            for (String name : List.of("prone:swing", "prone:swing:alt", "prone:throw")) {
                JsonObject anim = animations(model, "main.animation.json").getAsJsonObject(name);
                assertNotNull(anim, model + ": 缺少 " + name);
                JsonObject bones = anim.getAsJsonObject("bones");
                // 腿必须留给主频道的爬行循环：它跟着 MAllBody 一起侧转，不需要本动画再写一遍。
                List<String> mustNotWrite = List.of("LeftLeg", "LeftLowerLeg", "LeftFoot",
                        "RightLeg", "RightLowerLeg", "RightFoot", "DownBody", "Root");
                for (String leg : mustNotWrite) {
                    assertFalse(bones.has(leg), model + "/" + name + ": 不能写 " + leg
                            + (leg.contains("Leg") || leg.contains("Foot") || leg.equals("DownBody")
                            ? "（腿要继续爬行）" : ""));
                }
                assertTrue(bones.has("Head"), model + "/" + name + ": 必须自己写头（否则沿用站姿的头，会朝地）");
                assertTrue(bones.has("RightArm"), model + "/" + name + ": 必须写持械手");
                // 关键帧必须连续：相邻关键帧的任何分量都不允许跳超过 180 度。
                // 侧躺动画的通道是 molang 字符串（bone_rot(...)*(1-roll)+(目标)*roll），
                // 所以先把"目标常量"抽出来再比较——这正是曾经让手臂走 281 度长弧的那类 bug。
                for (var bone : bones.entrySet()) {
                    JsonElement rotationElement = bone.getValue().getAsJsonObject().get("rotation");
                    if (rotationElement == null || !rotationElement.isJsonObject()) {
                        continue;   // 静态通道（三元数组）没有关键帧可比较
                    }
                    JsonObject rotation = rotationElement.getAsJsonObject();
                    List<String> times = new ArrayList<>(rotation.keySet());
                    times.sort((a, b) -> Double.compare(Double.parseDouble(a), Double.parseDouble(b)));
                    for (int i = 1; i < times.size(); i++) {
                        JsonArray prev = rotation.getAsJsonObject(times.get(i - 1)).getAsJsonArray("post");
                        JsonArray cur = rotation.getAsJsonObject(times.get(i)).getAsJsonArray("post");
                        for (int axis = 0; axis < 3; axis++) {
                            double delta = Math.abs(number(cur.get(axis)) - number(prev.get(axis)));
                            assertTrue(delta <= 180.0,
                                    model + "/" + name + "/" + bone.getKey() + ": " + times.get(i - 1)
                                            + "->" + times.get(i) + " 轴 " + axis + " 跳了 " + delta
                                            + " 度（会让手臂瞬移/抽搐）");
                        }
                    }
                }
            }
        }
    }

    @Test
    void tacProneAnimationsLeaveTheLowerBodyToTheMainChannel() throws Exception {
        Set<String> lower = Set.of("Root", "AllBody", "DownBody",
                "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot");
        for (String model : MODELS) {
            var all = animations(model, "tac.animation.json");
            int checked = 0;
            for (var entry : all.entrySet()) {
                if (!entry.getKey().startsWith("tac:climb")) {
                    continue;
                }
                checked++;
                JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
                for (String bone : lower) {
                    assertFalse(bones.has(bone),
                            model + "/" + entry.getKey() + ": 不许写 " + bone
                                    + "——下肢必须只由主频道的 climb/climbing 驱动，否则开火会把爬行冻住");
                }
            }
            assertTrue(checked >= 18, model + ": tac:climb* 动画数量异常（" + checked + "）");
        }
    }
}
