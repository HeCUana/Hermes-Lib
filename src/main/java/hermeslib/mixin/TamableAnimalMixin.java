package hermeslib.mixin;

import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「驯服次数」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code TamableAnimal#tame(Player)} —— MC 26 起，狼/猫/鹦鹉/驴等统一走
 *       {@code net.minecraft.world.entity.TamableAnimal} 这条驯服链路。</li>
 * </ul>
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin
{
    @Inject(method = "tame", at = @At("HEAD"))
    private void hermes$onTame(Player player, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ANIMALS_TAMED, 1L);
    }
}
