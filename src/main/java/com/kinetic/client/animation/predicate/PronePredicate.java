package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.ProneAction;
import com.kinetic.client.animation.condition.InnerClassify;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

/**
 * 匍匐侧躺频道。
 *
 * <p>注册在 {@code use} 之后，因此它在同一帧里晚于 use / hold / fire / swing 处理，
 * 写下的骨骼就是最终值。选哪段动画只看"这一轮是什么动作"（{@link ProneAction.Kind}），
 * 姿态的全部细节在动画数据里。
 *
 * <p>动作种类由其他频道<b>本帧正在播的动画</b>推断，而不是去读枪械模组的内部状态：
 * <ul>
 *   <li>{@code player.hold_mainhand} 在播 {@code tac:mainhand:grenade} → 手雷蓄力；</li>
 *   <li>{@code player.use} 在播 {@code *:medical} → 打药；</li>
 *   <li>{@code player.fire} 在播 {@code tac:reload:*} → 换弹；</li>
 *   <li>{@code player.swing} 在播 {@code prone:swing} / {@code prone:swing:alt} 或 {@code prone:throw} → 挥砍 / 投掷；</li>
 *   <li>{@code player.use} 在播 {@code *:eat} / {@code *:drink} → 吃喝（只需要把头腿摆正）。</li>
 * </ul>
 * 换弹动画是一次性的，播完就离开 RUNNING；TaCZ 的重载计数还在时用它兜底。
 */
public class PronePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    static final String CONTROLLER_SWING = "player.swing";
    static final String CONTROLLER_USE = "player.use";
    static final String CONTROLLER_FIRE = "player.fire";
    static final String CONTROLLER_HOLD = "player.hold_mainhand";

    static final String IDLE_ANIMATION = "prone:action:idle";
    /** 匍匐侧躺挥砍动画名；本频道不重复写 swing 频道的手臂弧线。 */
    static final String PRONE_SWING = "prone:swing";
    static final String PRONE_SWING_ALT = "prone:swing:alt";
    /** 由 swing 频道播放的匍匐投掷动画；本频道不重复写它。 */
    static final String THROW_ANIMATION = "prone:throw";

    /** 物品分类表里"医疗/注射类消耗品"的键，与 {@code use_mainhand:medical} 动画名同源。 */
    private static final String MEDICAL_CLASS = "medical";

    /**
     * 兜底识别用的物品 id 关键词。
     *
     * <p>前面的判定依赖联动模组的接口（{@code TACZCompat#isGrenade} 等）或物品分类表，一旦某个
     * 消耗品不是那几家的、也没进分类表，趴下时就会被判成"没有动作"，退到通用姿势——表现正是
     * 玩家说的"趴下打药/扔雷还是站立时的动作"。这里按注册名兜一层，不依赖任何模组。
     */
    private static final String[] GRENADE_HINTS = {
            "grenade", "nade", "frag", "c4", "claymore", "dynamite", "semtex"};
    private static final String[] MEDICAL_HINTS = {
            "medkit", "medic", "bandage", "syringe", "inject", "morphine", "firstaid",
            "healthkit", "stimulant", "antidote", "tourniquet"};

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        Entity entity = event.getAnimatableEntity().getEntity();
        if (!(entity instanceof Player player)) {
            return PlayState.STOP;
        }
        ProneAction.Kind kind = detect(event, player);
        float roll = ProneAction.observe(player, event.getRenderTicks(), kind);
        if (roll <= ProneAction.EPSILON) {
            return PlayState.STOP;
        }
        String animation = animationFor(event.getAnimatableEntity(), ProneAction.kindOf(player));
        if (animation == null) {
            return PlayState.STOP;
        }
        return playAnimation(event, animation, LoopType.LOOP);
    }

    /** 本帧的动作种类；{@code NONE} 表示没有正在进行的动作。 */
    static ProneAction.Kind detect(AnimationEvent<? extends CustomHumanoidEntity<?>> event, Player player) {
        ProneAction.Kind swingKind = kindForSwingAnimation(currentAnimation(event, CONTROLLER_SWING));
        if (swingKind != ProneAction.Kind.NONE) {
            return swingKind;
        }
        // 正在使用物品：优先读玩家自己的状态，而不是某个频道"恰好"把动画名选成我们认识的样子。
        // 只看动画名会漏掉一大类情况：趴下按右键掐雷时 hold 频道播的是 tac:hold:grenade（瞄准/持握），
        // 打药时 use 频道也可能被条件表接管成别的名字——表现就是"趴着按不出来，得先站起来做一次再趴下"。
        // 这条判定不依赖任何联动模组的分支细节，因此对 TaCZ 之外的枪械/消耗品联动同样成立。
        if (player.isUsingItem()) {
            ItemStack using = player.getUseItem();
            if (isGrenade(using) || idLooksLike(using, GRENADE_HINTS)) {
                return ProneAction.Kind.GRENADE;
            }
            if (MEDICAL_CLASS.equals(InnerClassify.getClassify(using))
                    || idLooksLike(using, MEDICAL_HINTS)) {
                return ProneAction.Kind.MEDICAL;
            }
            UseAnim useAnim = using.getUseAnimation();
            if (useAnim == UseAnim.EAT || useAnim == UseAnim.DRINK) {
                return ProneAction.Kind.OTHER;
            }
        }
        // 兼容路径：不通过 isUsingItem 表达蓄力的模组，仍按频道动画名识别。
        String hold = currentAnimation(event, CONTROLLER_HOLD);
        if (hold != null && hold.startsWith("tac:mainhand:grenade")) {
            return ProneAction.Kind.GRENADE;
        }
        // 蓄力不经过 isUsingItem 的模组：手持物本身像手雷就按手雷蓄力处理
        if (idLooksLike(player.getMainHandItem(), GRENADE_HINTS)) {
            return ProneAction.Kind.GRENADE;
        }
        String use = currentAnimation(event, CONTROLLER_USE);
        if (use != null) {
            if (use.contains(":medical") || use.contains(":inject")) {
                return ProneAction.Kind.MEDICAL;
            }
            if (use.endsWith(":eat") || use.endsWith(":drink")) {
                return ProneAction.Kind.OTHER;
            }
        }
        String fire = currentAnimation(event, CONTROLLER_FIRE);
        if (fire != null && fire.startsWith("tac:reload:")) {
            return ProneAction.Kind.RELOAD;
        }
        if (isReloading(player)) {
            return ProneAction.Kind.RELOAD;
        }
        return ProneAction.Kind.NONE;
    }


    private static boolean isGrenade(ItemStack stack) {
        return TACZCompat.isGrenade(stack) || SWarfareCompat.isGrenade(stack);
    }

    /** 将 swing 频道的侧躺动画映射为需要侧滚的动作种类。 */
    public static ProneAction.Kind kindForSwingAnimation(@Nullable String animation) {
        if (THROW_ANIMATION.equals(animation)) {
            return ProneAction.Kind.THROW;
        }
        if (PRONE_SWING.equals(animation) || PRONE_SWING_ALT.equals(animation)) {
            return ProneAction.Kind.SWING;
        }
        return ProneAction.Kind.NONE;
    }

    /** 物品注册名里是否含有关键词（不依赖任何联动模组）。 */
    static boolean idLooksLike(ItemStack stack, String[] hints) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        for (String hint : hints) {
            if (path.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReloading(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }
        return TACZCompat.isReloading(player) || SWarfareCompat.isReloading(player);
    }

    /** 这一轮对应的动画名；模型没有该动画时回退到通用姿势，再没有就不接这一帧。 */
    @Nullable
    static String animationFor(CustomHumanoidEntity<?> animatable, ProneAction.Kind kind) {
        return animationNameFor(kind, name -> animatable.getAnimation(name) != null);
    }

    /**
     * 动作种类 → 动画名的映射（纯函数，便于离线验证回退规则）。
     *
     * <p>模型可能只有通用的 {@code prone:action:idle}（自建模型常常只改这部分），
     * 那时侧滚仍然成立、头和腿照样被摆正，只是手臂留给原动作动画。
     */
    @Nullable
    public static String animationNameFor(ProneAction.Kind kind, java.util.function.Predicate<String> available) {
        String name = switch (kind) {
            case MEDICAL -> "prone:action:medical";
            case RELOAD -> "prone:action:reload";
            case GRENADE -> "prone:action:grenade";
            case OTHER, NONE -> IDLE_ANIMATION;
            case SWING, THROW -> null;
        };
        if (name == null) {
            // 投掷与挥砍姿势完全由 swing 频道负责，本频道只管把 roll 推上去。
            return null;
        }
        if (available.test(name)) {
            return name;
        }
        return available.test(IDLE_ANIMATION) ? IDLE_ANIMATION : null;
    }

    /** 某个控制器本帧正在播的动画名。控制器不存在（模型没这套频道）时为 null。 */
    @Nullable
    static String currentAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, String controllerName) {
        List<IAnimationController> controllers = event.getAnimatableEntity().getAnimationData().getAnimationControllers();
        for (int i = 0; i < controllers.size(); i++) {
            IAnimationController<?> controller = controllers.get(i);
            if (controllerName.equals(controller.getName())) {
                return controller.getCurrentAnimationName();
            }
        }
        return null;
    }
}
