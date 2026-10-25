package hermeslib.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import hermeslib.api.Metric;
import hermeslib.stats.InteractionTracker;
import hermeslib.stats.HermesStatsManager;

import java.util.UUID;

/**
 * 采集「动物繁殖次数」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code Animal#mobInteract(Player, InteractionHand)} —— 记录「谁 interact 了这只动物」；</li>
 *   <li>{@code Animal#spawnChildFromBreeding(ServerLevel, Animal)} —— 繁殖真正发生。</li>
 * </ul>
 *
 * <p>原版的繁殖结算里没有玩家信息（只有两只亲本），
 * 因此这里靠 {@link InteractionTracker} 回溯出喂食者，
 * 找不到时退化为「附近最近的玩家」。</p>
 */
@Mixin(Animal.class)
public abstract class AnimalMixin
{
    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void hermes$onMobInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir)
    {
        InteractionTracker.record(((Animal) (Object) this).getUUID(), player.getUUID());
    }

    @Inject(method = "spawnChildFromBreeding", at = @At("HEAD"))
    private void hermes$onSpawnChildFromBreeding(ServerLevel level, Animal partner, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        Animal self = (Animal) (Object) this;
        ServerPlayer breeder = resolveBreeder(level, self, partner);
        if (breeder != null)
        {
            manager.add(breeder, Metric.ANIMALS_BRED, 1L);
        }
    }

    private static ServerPlayer resolveBreeder(ServerLevel level, Animal self, Animal partner)
    {
        UUID breederId = InteractionTracker.lookup(self.getUUID());
        if (breederId == null && partner != null)
        {
            breederId = InteractionTracker.lookup(partner.getUUID());
        }
        if (breederId != null)
        {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(breederId);
            if (player != null) return player;
        }

        // 兜底：8 格内最近的玩家
        ServerPlayer nearest = null;
        double bestDistance = 64.0D;
        for (ServerPlayer candidate : level.getServer().getPlayerList().getPlayers())
        {
            if (candidate.level() != level) continue;
            double distance = candidate.distanceToSqr(self);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
