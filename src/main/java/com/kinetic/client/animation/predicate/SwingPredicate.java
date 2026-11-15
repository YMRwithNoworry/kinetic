package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.EntityTickStates;
import com.kinetic.client.animation.ProneAction;
import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.animation.condition.ConditionManager;
import com.kinetic.client.animation.condition.ConditionalSwing;
import com.kinetic.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;
import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimationSeeked;

public class SwingPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    /** 匍匐挥砍动画名（模型自带才会接管；没有就保持原行为）。 */
    static final String PRONE_SWING = "prone:swing";
    /** 匍匐投掷动画名（手雷/药水等 throwable 的挥动）。 */
    static final String PRONE_THROW = "prone:throw";
    /** 匍匐挥砍的第二段（反手）；模型没有就退回 {@link #PRONE_SWING}。 */
    static final String PRONE_SWING_ALT = "prone:swing:alt";

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 滑铲锁定：持枪时左键触发的挥动不允许改手臂姿势（滑铲的下身由锁定层负责）。
        if (SlideLock.suppressesOneShot(entity)) {
            return PlayState.STOP;
        }

        // 铁魔法兼容
        PlayState playState = IronsSpellBooksCompat.playAnimation(event, entity);
        if (playState != null) {
            return playState;
        }

        // 拔刀剑兼容，拔刀剑的使用不受 swing 限制
        if (!entity.isSleeping() && SlashBladeCompat.isSlashBladeItem(entity.getItemInHand(InteractionHand.MAIN_HAND))) {
            // 起手阻止后续原挥剑动画
            if (event.getCodedController().isAnimFinished()) {
                // 重置动画
                event.getCodedController().indicateReload();
            }
            String animationName = SlashBladeCompat.getAnimationName(event);
            if (StringUtils.isNoneBlank(animationName)) {
                if (event.getAnimatableEntity().getAnimation(animationName) != null) {
                    return playAnimation(event, animationName);
                }
                return PlayState.CONTINUE;
            }
        }

        if (entity.swinging && !entity.isSleeping()) {
            if (entity.swingTime == 0 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.SWING)) {
                // 连招窗口：上一段已经挥出、还没收完（进度至少 70%）时再触发才接下一段；
                // 太早的连点不推进段数，而是把当前这一段重放一遍——否则会从半空里硬切到
                // 另一段的起手姿势。
                float progress = swingProgress(event);
                // swing 开始时重置动画
                event.getCodedController().indicateReload();
                // 趴下挥砍和站姿一样两段交替
                if (entity.swingingArm == InteractionHand.MAIN_HAND
                        && entity instanceof net.minecraft.world.entity.player.Player p) {
                    ProneAction.beginSwing(p, progress >= 0.70F);
                }
            }
            ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
            ConditionalSwing conditionalSwing = entity.swingingArm == InteractionHand.MAIN_HAND
                    ? conditionManager.getSwingMainhand() : conditionManager.getSwingOffhand();
            String name = null;
            if (conditionalSwing != null) {
                name = genericSwingFor(conditionalSwing.doTest(entity, entity.swingingArm), entity.swingingArm);
            }
            if (StringUtils.isBlank(name)) {
                name = (entity.swingingArm == InteractionHand.MAIN_HAND) ? "swing_hand" : "swing_offhand";
            }
            // 趴下时换成匍匐专用的弧线：站姿弧线（尤其投掷）叠到躺姿上会往地里砍/往地上扔。
            // 每帧按趴姿重新判定，并且**跨姿态时按进度接力**：玩家站着挥到一半趴下（或趴着挥到
            // 一半站起来），新换上的那段从相同进度继续，而不是回到第 0 帧重来。
            String target = name;
            if (entity.swingingArm == InteractionHand.MAIN_HAND && ProneAction.isProne(entity)) {
                // 投掷：站姿名里带 throwable_potion，或者手里那把东西本身就是手雷/投掷物
                // （很多投掷模组的挥动名并不叫 throwable_potion）。
                boolean throwing = name.contains("throwable_potion")
                        || PronePredicate.idLooksLike(entity.getMainHandItem(),
                        new String[]{"grenade", "nade", "frag", "c4", "claymore", "dynamite", "semtex",
                                "potion", "bomb", "throwing"});
                String variant = throwing ? PRONE_THROW : PRONE_SWING;
                if (!throwing
                        && entity instanceof net.minecraft.world.entity.player.Player p
                        && ProneAction.isAlternateSwing(p)
                        && event.getAnimatableEntity().getAnimation(PRONE_SWING_ALT) != null) {
                    variant = PRONE_SWING_ALT;
                }
                if (event.getAnimatableEntity().getAnimation(variant) != null) {
                    target = variant;
                }
            }
            return playAnimationSeeked(event, target, swingProgress(event));
        }
        // 挥动已经结束（entity.swinging 窗口只有 6 tick，而趴下弧线可能更长）：控制器会继续把
        // prone:swing / prone:throw 播完，并且一直覆盖右臂。玩家如果在这段里站起来，就会看到
        // 站着的人还在做趴下挥砍。因此一旦不再趴着，立刻停止趴下变体，让上层姿势接管。
        if (shouldStopProneVariant(ProneAction.isProne(entity),
                event.getCodedController().getCurrentAnimationName())) {
            return PlayState.STOP;
        }
        return PlayState.CONTINUE;
    }

    /**
     * 当前挥动动画播到哪了（0~1）。
     *
     * <p>用来在"趴下 ↔ 站立"切换时把进度传给另一段弧线：站姿挥动长 0.375s、侧躺挥砍长 0.62s，
     * 投掷时则使用资源自身的长度；按比例映射后接着播。拿不到长度就返回 0——那等于从头开始，与改动前一致。
     */
    static float swingProgress(AnimationEvent<CustomHumanoidEntity<?>> event) {
        var controller = event.getCodedController();
        float length = controller.getAnimationLength(controller.getCurrentAnimationName());
        if (length <= 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, Math.max(0.0F, controller.getAnimationTicks() / length));
    }

    /**
     * 挥动结束后，是否应当立刻停掉正在播的趴下变体。
     *
     * <p>控制器在 {@code entity.swinging} 变 false 之后仍会 {@code CONTINUE} 把当前动画播完，
     * 而趴下弧线比原版 6 tick 的挥动窗口长，所以站起来的瞬间它还在覆盖右臂。规则只有一条：
     * **不再趴着、且当前播的是趴下变体** → 停。还趴着就让它播完（那正是要的效果）。
     *
     * @param prone            此刻是否趴着
     * @param currentAnimation 控制器当前动画名（可能为 null）
     */
    static boolean shouldStopProneVariant(boolean prone, @Nullable String currentAnimation) {
        if (prone || currentAnimation == null) {
            return false;
        }
        return PRONE_SWING.equals(currentAnimation)
                || PRONE_SWING_ALT.equals(currentAnimation)
                || PRONE_THROW.equals(currentAnimation);
    }

    /**
     * 剑类挥砍回落到通用手臂挥砍。
     *
     * <p>模型自带的 {@code swing:sword} 是一套带 {@code UpBody}/{@code UpperBody}/{@code Head} 轨道的
     * 三段连招，而且分支靠 timeline 里的 {@code v.jump = q.is_jumping} 在动画第 0 帧算一次——
     * 控制器复用同一段动画时这个值会滞留，于是一挥就是跳劈变体；同时躯干轨道会盖掉跑步的上半身
     * 摆动，看起来像"攻击把跑步打断了"。
     *
     * <p>玩家要的是只动手臂的挥砍，所以剑类统一回到 {@code swing_hand}/{@code swing_offhand}
     * （这两条只写 {@code RightArm}/{@code RightForeArm}，不碰躯干与腿），剑的持握姿势仍由
     * {@code hold_mainhand:sword} 负责，不受影响。
     */
    private static String genericSwingFor(String name, InteractionHand hand) {
        if (name == null) {
            return null;
        }
        // swing:slashblade 是空动画（只剩 loop、没有骨骼）。被归到 slashblade 分类的近战武器
        // （lrtactical:melee 就在这类里）不能退回 swing_hand，否则劈砍会变成原版的小挥臂；
        // 统一改用刀剑劈砍 swing:sword。
        if ("swing:slashblade".equals(name)) {
            return hand == InteractionHand.MAIN_HAND ? "swing:sword" : "swing_offhand";
        }
        return name;
    }
}
