package com.kinetic.client.compat.ragdollified;

import com.kinetic.Kinetic;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家死亡时：先把「死亡瞬间的关节姿态」注入 Ragdollified；客户端不立即重复生成，稍后仅作服务端缺失时的兜底。
 *
 * <p><b>注意</b>：Forge 只在 {@code LivingEntity#die} 里发出 {@code LivingDeathEvent}，而客户端玩家
 * 死亡不会走那条路（见 {@link RagdollifiedDeathWatcher}），所以玩家尸体的主要入口是每 tick 的
 * 状态观察；这里保留事件入口，覆盖「确实在客户端调用了 die()」的情形（例如其它模组）。
 *
 * <p>优先级必须是 HIGHEST：Ragdollified 的 {@code ClientDeathHandler} 用默认优先级，
 * 而它的 {@code resolvePose} 只在没有「新鲜姿态」时才会自行捕获。姿态必须先于它读取，
 * 否则它捕获失败后会退回预设的直立姿态。
 */
@Mod.EventBusSubscriber(modid = Kinetic.MOD_ID, value = Dist.CLIENT)
public final class RagdollifiedEvent {
    private RagdollifiedEvent() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!RagdollifiedCompat.isAvailable()) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.client.player.AbstractClientPlayer player)
                || !player.level().isClientSide) {
            return;
        }
        // 带上致死一击的伤害来源：提供方据此还原死亡方向与命中冲量，缺了它尸体不会被击退。
        RagdollifiedCompat.handleClientDeath(player, event.getSource());
    }
}
