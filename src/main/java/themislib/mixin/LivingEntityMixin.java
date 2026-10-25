package themislib.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import themislib.api.Metric;
import themislib.collector.ThemisCollectors;
import themislib.stats.ThemisStatsManager;

/**
 * 采集「死亡」「击杀」「造成伤害」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code LivingEntity#die(DamageSource)} —— 死亡结算（玩家死亡也会经过这里：
 *       {@code Player#die} → {@code Avatar#die} → {@code LivingEntity#die}，只会触发一次）；</li>
 *   <li>{@code LivingEntity#actuallyHurt(ServerLevel, DamageSource, float)} —— 实际扣血，
 *       这里统计「玩家对生物造成的伤害」。</li>
 * </ul>
 *
 * <p><b>注意</b>：{@code Player} 覆写了 {@code actuallyHurt}，所以玩家受到/造成的伤害
 * 由 {@link PlayerMixin} 负责，此处只处理生物。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin
{
    @Inject(method = "die", at = @At("HEAD"))
    private void themis$onDie(DamageSource source, CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;

        LivingEntity self = (LivingEntity) (Object) this;

        // 死亡数（玩家死亡也会走到这里）
        if (self instanceof ServerPlayer victim)
        {
            manager.add(victim, Metric.DEATHS, 1L);
        }

        // 击杀归属
        ServerPlayer killer = ThemisCollectors.attackerOf(source);
        if (killer != null)
        {
            if (self instanceof ServerPlayer)
            {
                manager.add(killer, Metric.PLAYER_KILLS, 1L);
            }
            else
            {
                manager.add(killer, Metric.MOB_KILLS, 1L);
            }
        }
    }

    /** 玩家对生物造成的伤害。 */
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void themis$onActuallyHurt(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount,
            CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;

        ServerPlayer attacker = ThemisCollectors.attackerOf(source);
        if (attacker == null) return;
        manager.add(attacker, Metric.DAMAGE_DEALT, Math.round(amount));
    }
}
