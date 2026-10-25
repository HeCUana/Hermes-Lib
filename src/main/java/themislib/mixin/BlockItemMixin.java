package themislib.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import themislib.api.Metric;
import themislib.stats.PlacedBlockTracker;
import themislib.stats.ThemisStatsManager;

/**
 * 采集「放置方块」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code BlockItem#place(BlockPlaceContext)} —— 方块真正被放下的瞬间。</li>
 * </ul>
 *
 * <p>这里同时把坐标写进 {@link PlacedBlockTracker}，
 * 供「仅统计自然方块」的挖掘过滤使用。</p>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin
{
    @Inject(method = "place", at = @At("RETURN"))
    private void themis$onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir)
    {
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) return;

        Player player = context.getPlayer();
        if (player == null) return;  // 发射器等非玩家来源不统计

        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;

        manager.add(player, Metric.BLOCKS_PLACED, 1L);

        if (player.level() instanceof ServerLevel serverLevel)
        {
            PlacedBlockTracker.markPlaced(serverLevel, context.getClickedPos());
        }
    }
}
