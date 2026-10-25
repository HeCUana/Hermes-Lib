package hermeslib.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「钓获次数」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code FishingHook#catchingFish(BlockPos)} —— 钓上来战利品（鱼/宝藏/垃圾）的那一刻。</li>
 * </ul>
 *
 * <p>归属玩家取自 {@code FishingHook#getPlayerOwner()}，因此玩家离线前收回鱼竿也会正确计数。</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin
{
    @Inject(method = "catchingFish", at = @At("HEAD"))
    private void hermes$onCatchingFish(BlockPos pos, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        Player owner = ((FishingHook) (Object) this).getPlayerOwner();
        if (owner == null) return;
        manager.add(owner, Metric.FISH_CAUGHT, 1L);
    }
}
