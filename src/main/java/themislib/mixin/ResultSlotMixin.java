package themislib.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import themislib.api.Metric;
import themislib.stats.ThemisStatsManager;

/**
 * 采集「制作（合成）产出数量」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ResultSlot#onTake(Player, ItemStack)} —— 玩家从合成结果槽取出产物。</li>
 * </ul>
 *
 * <p>2×2 背包合成、工作台 3×3 合成走的都是这个槽位。</p>
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin
{
    @Inject(method = "onTake", at = @At("HEAD"))
    private void themis$onTake(Player player, ItemStack stack, CallbackInfo ci)
    {
        if (stack == null || stack.isEmpty()) return;
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_CRAFTED, stack.getCount());
    }
}
