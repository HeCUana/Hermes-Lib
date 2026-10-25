package hermeslib.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import hermeslib.api.Metric;
import hermeslib.collector.HermesCollectors;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「跳跃次数」「丢弃物品」「死亡数」「击杀玩家数」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ServerPlayer#jumpFromGround()}；</li>
 *   <li>{@code ServerPlayer#drop(ItemStack, boolean, Prediction)} —— Q 键丢弃；</li>
 *   <li>{@code ServerPlayer#die(DamageSource)} —— 玩家死亡结算。</li>
 * </ul>
 *
 * <p><b>为什么挂在 ServerPlayer 而不是 LivingEntity</b>：
 * {@code ServerPlayer} 覆写了这些方法且 {@code die} 不调用父类实现
 * （26.3 中是完全覆写：死亡消息、掉落、统计等全部自己处理），挂在父类上抓不到玩家。</p>
 *
 * <p>死亡掉落走的是 {@code LivingEntity#dropAllDeathLoot}，不会经过 {@code drop()}，
 * 因此本 Mixin 统计到的只有玩家主动丢弃的物品。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin
{
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void hermes$onJump(CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add((ServerPlayer) (Object) this, Metric.JUMPS, 1L);
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void hermes$onDie(DamageSource source, CallbackInfo ci)
    {
        ServerPlayer victim = (ServerPlayer) (Object) this;
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        manager.add(victim, Metric.DEATHS, 1L);

        // 击杀归属：有明确的玩家加害者才计一次击杀（生物受害者走 LivingEntityMixin 的 MOB_KILLS）
        ServerPlayer killer = HermesCollectors.attackerOf(source);
        if (killer != null)
        {
            manager.add(killer, Metric.PLAYER_KILLS, 1L);
        }
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"))
    private void hermes$onDrop(ItemStack stack, boolean randomize, Prediction prediction,
            CallbackInfoReturnable<ItemEntity> cir)
    {
        if (stack == null || stack.isEmpty()) return;
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add((ServerPlayer) (Object) this, Metric.ITEMS_DROPPED, stack.getCount());
    }
}
