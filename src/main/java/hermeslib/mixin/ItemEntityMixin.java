package hermeslib.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「拾取物品数量」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ItemEntity#playerTouch(Player)} —— 玩家碰到掉落物。</li>
 * </ul>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin
{
    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void hermes$onPlayerTouch(Player player, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        ItemEntity self = (ItemEntity) (Object) this;
        int count = self.getItem().getCount();
        if (count <= 0) return;
        manager.add(player, Metric.ITEMS_PICKED_UP, count);
    }
}
