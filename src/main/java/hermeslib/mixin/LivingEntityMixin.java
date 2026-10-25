package hermeslib.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.collector.HermesCollectors;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「击杀生物」「造成伤害」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code LivingEntity#die(DamageSource)} —— 生物死亡结算，这里只做「击杀生物」归属。
 *       <b>注意</b>：26.3 中 {@code ServerPlayer#die} 是完全覆写且不调用父类实现，
 *       玩家死亡根本不会经过这里 —— 玩家死亡数与击杀玩家数由 {@link ServerPlayerMixin}
 *       挂在 {@code ServerPlayer#die} 上统计；</li>
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
    private void hermes$onDie(DamageSource source, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        LivingEntity self = (LivingEntity) (Object) this;

        // 击杀归属：生物受害者 → 击杀生物（玩家受害者不走这里，见 ServerPlayerMixin）
        if (!(self instanceof ServerPlayer))
        {
            ServerPlayer killer = HermesCollectors.attackerOf(source);
            if (killer != null)
            {
                manager.add(killer, Metric.MOB_KILLS, 1L);
            }
        }
    }

    /** 玩家对生物造成的伤害。 */
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void hermes$onActuallyHurt(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount,
            CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        // 过滤 /kill 的 Float.MAX_VALUE 伤害与异常大数值
        int damage = HermesCollectors.countableDamage(source, amount);
        if (damage <= 0) return;

        ServerPlayer attacker = HermesCollectors.attackerOf(source);
        if (attacker == null) return;
        manager.add(attacker, Metric.DAMAGE_DEALT, damage);
    }
}
