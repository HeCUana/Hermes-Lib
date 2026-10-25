package themislib.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import themislib.api.Metric;
import themislib.stats.ThemisStatsManager;

/**
 * 采集「村民交易次数」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code MerchantResultSlot#onTake(Player, ItemStack)} —— 交易成交的那一刻
 *       （原版在这里调用 {@code Merchant#notifyTrade}）。</li>
 * </ul>
 *
 * <p>计数的是「成交笔数」，不是交易物品数量。</p>
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin
{
    @Inject(method = "onTake", at = @At("HEAD"))
    private void themis$onTake(Player player, ItemStack stack, CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.VILLAGER_TRADES, 1L);
    }
}
