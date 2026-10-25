package hermeslib.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.collector.HermesCollectors;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集玩家身上的伤害结算。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code Player#actuallyHurt(ServerLevel, DamageSource, float)}。</li>
 * </ul>
 *
 * <p><b>为什么单独一个 Mixin</b>：{@code Player} 覆写了 {@code actuallyHurt}
 * 且没有调用父类实现，所以在 {@code LivingEntity} 上注入是抓不到玩家的。</p>
 */
@Mixin(Player.class)
public abstract class PlayerMixin
{
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void hermes$onActuallyHurt(ServerLevel level, DamageSource source, float amount, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        // 玩家受到的伤害
        if ((Object) this instanceof ServerPlayer victim)
        {
            manager.add(victim, Metric.DAMAGE_TAKEN, Math.round(amount));
        }

        // 玩家对玩家造成的伤害（对生物的伤害在 LivingEntityMixin 里统计）
        ServerPlayer attacker = HermesCollectors.attackerOf(source);
        if (attacker != null)
        {
            manager.add(attacker, Metric.DAMAGE_DEALT, Math.round(amount));
        }
    }
}
