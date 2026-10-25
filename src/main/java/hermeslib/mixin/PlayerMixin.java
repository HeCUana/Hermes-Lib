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
 * 采集玩家身上的伤害结算，以及「实际钓到的鱼数量」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code Player#actuallyHurt(ServerLevel, DamageSource, float)} —— 伤害结算；</li>
 *   <li>{@code Player#awardStat(Identifier, int)} —— 原版自定义统计（钓鱼用 {@code Stats.FISH_CAUGHT}）。</li>
 * </ul>
 *
 * <p><b>为什么单独一个 Mixin</b>：{@code Player} 覆写了 {@code actuallyHurt}
 * 且没有调用父类实现，所以在 {@code LivingEntity} 上注入是抓不到玩家的。</p>
 *
 * <p><b>钓鱼为什么挂在 awardStat 上</b>：原版在鱼真正上钩时才发放
 * {@code minecraft:fish_caught} 统计（且只有 {@code ItemTags.FISHES} 里的鱼才算，
 * 垃圾与宝藏不算），这正好就是「实际掉的鱼的数量」；
 * 而浮标在水里等待的每个 tick 都会走 {@code FishingHook#catchingFish}，
 * 挂在那里只会统计到「垂钓时长」。</p>
 */
@Mixin(Player.class)
public abstract class PlayerMixin
{
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void hermes$onActuallyHurt(ServerLevel level, DamageSource source, float amount, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        // 过滤 /kill 的 Float.MAX_VALUE 伤害与异常大数值
        int damage = HermesCollectors.countableDamage(source, amount);
        if (damage <= 0) return;

        // 玩家受到的伤害
        if ((Object) this instanceof ServerPlayer victim)
        {
            manager.add(victim, Metric.DAMAGE_TAKEN, damage);
        }

        // 玩家对玩家造成的伤害（对生物的伤害在 LivingEntityMixin 里统计）
        ServerPlayer attacker = HermesCollectors.attackerOf(source);
        if (attacker != null)
        {
            manager.add(attacker, Metric.DAMAGE_DEALT, damage);
        }
    }
}
