package com.kinetic.client.compat.ragdollified;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * 客户端玩家死亡探测：每 tick 观察客户端玩家是否进入「死亡」状态，并在第一次观察到时触发 KINETIC 的
 * 布娃娃联动（捕获死亡姿态 + 等待服务端生成，超时后客户端兜底）。
 *
 * <p><b>为什么不监听 {@code LivingDeathEvent}</b>：该事件只在 {@code LivingEntity#die} 里发出，而客户端
 * 的 {@code LivingEntity#hurt} 在 {@code level().isClientSide} 时立即返回，{@code handleEntityEvent(3)}
 * 又显式跳过玩家（{@code if (!(this instanceof Player))}），因此玩家死亡时客户端根本收不到这个事件。
 * 死亡状态在客户端是通过生命值同步（{@code isDeadOrDying()}）体现的，所以这里观察状态而不是事件。
 *
 * <p>只观察客户端玩家列表：提供方对怪物仍有自己的客户端死亡路径，KINETIC 也不接管怪物布娃娃。
 * 未安装提供方时本类不做任何工作，也不会触碰提供方的任何类型。
 */
public final class RagdollifiedDeathWatcher {
    /** 已经处理过「本次死亡」的实体 id；玩家复活后移除，实体离开客户端世界后一并清理。 */
    private static final IntOpenHashSet HANDLED = new IntOpenHashSet();
    private static final IntOpenHashSet PRESENT = new IntOpenHashSet();

    private RagdollifiedDeathWatcher() {
    }

    /** 客户端每 tick 调用一次。 */
    public static void tick() {
        if (!RagdollifiedCompat.isInstalled()) {
            return;
        }
        // 排队过的布娃娃要回头确认真的出现了：没出现就把死亡动画还回去，别让尸体凭空消失。
        RagdollifiedCompat.verifyQueuedRagdolls();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            HANDLED.clear();
            return;
        }
        PRESENT.clear();
        for (AbstractClientPlayer player : level.players()) {
            int entityId = player.getId();
            PRESENT.add(entityId);
            if (!markIfNewDeath(entityId, player.isDeadOrDying(), HANDLED)) {
                continue;
            }
            // 死亡当刻先注入姿态并安排服务端等待；客户端兜底在 6 tick 后触发，早于玩家实体被移除。
            RagdollifiedCompat.handleClientDeath(player);
        }
        // 实体离开客户端世界（重生、换维度、断线）后不留陈旧条目，避免 id 复用时漏掉下一次死亡。
        HANDLED.retainAll(PRESENT);
    }

    /**
     * 纯状态迁移：该实体是否「刚刚」进入死亡。
     *
     * <p>返回 true 表示这是本次死亡的第一次观察；同一实体重复死亡（复活后再死）会再次返回 true，
     * 因为复活期间不再处于死亡状态，条目已被清除。
     */
    static boolean markIfNewDeath(int entityId, boolean dead, IntSet handled) {
        if (!dead) {
            handled.remove(entityId);
            return false;
        }
        return handled.add(entityId);
    }
}
