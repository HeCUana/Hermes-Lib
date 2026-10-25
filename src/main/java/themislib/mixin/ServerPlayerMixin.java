package themislib.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import themislib.api.Metric;
import themislib.stats.ThemisStatsManager;

/**
 * 采集「跳跃次数」「丢弃物品」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ServerPlayer#jumpFromGround()}；</li>
 *   <li>{@code ServerPlayer#drop(ItemStack, boolean, Prediction)} —— Q 键丢弃。</li>
 * </ul>
 *
 * <p><b>为什么挂在 ServerPlayer 而不是 LivingEntity</b>：
 * {@code ServerPlayer} 覆写了这两个方法，挂在父类上抓不到玩家。</p>
 *
 * <p>死亡掉落走的是 {@code LivingEntity#dropAllDeathLoot}，不会经过 {@code drop()}，
 * 因此本 Mixin 统计到的只有玩家主动丢弃的物品。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin
{
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void themis$onJump(CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.add((ServerPlayer) (Object) this, Metric.JUMPS, 1L);
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"))
    private void themis$onDrop(ItemStack stack, boolean randomize, Prediction prediction,
            CallbackInfoReturnable<ItemEntity> cir)
    {
        if (stack == null || stack.isEmpty()) return;
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.add((ServerPlayer) (Object) this, Metric.ITEMS_DROPPED, stack.getCount());
    }
}
