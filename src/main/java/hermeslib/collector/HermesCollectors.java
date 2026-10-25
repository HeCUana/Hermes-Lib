package hermeslib.collector;

import hermeslib.HermesLibMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * 采集过程中用到的公共工具方法。
 */
public final class HermesCollectors
{
    /**
     * 单次伤害计入统计的上限。
     *
     * <p>正常战斗的伤害最多也就几千；超过这个值的（例如 {@code /damage @s 2000000000}）
     * 一律不计入，避免把统计数据写坏。</p>
     */
    public static final long MAX_COUNTABLE_DAMAGE = 1_000_000L;

    private HermesCollectors()
    {
    }

    /**
     * 计算这次伤害是否应该计入统计，以及计入多少。
     *
     * <p><b>为什么要有这个过滤</b>：MC 26.3 的 {@code LivingEntity#kill(ServerLevel)}
     * 是这样实现「杀死」的：</p>
     * <pre>{@code
     * this.hurtServer(level, this.damageSources().genericKill(), Float.MAX_VALUE);
     * }</pre>
     * 也就是说 {@code /kill} 会造成一次 {@code Float.MAX_VALUE}（3.4e38）的伤害，
     * 直接 {@code Math.round} 会得到 {@code 2147483647}（21 亿）这种荒谬数值。
     * 这里把 {@code /kill} 的伤害以及异常大的数值一并排除。</p>
     *
     * @param source 伤害来源
     * @param amount 原始伤害值
     * @return 需要计入的伤害点数；0 表示本次伤害不计入统计
     */
    public static int countableDamage(DamageSource source, float amount)
    {
        if (source == null) return 0;
        if (amount <= 0 || !Float.isFinite(amount)) return 0;
        // /kill 造成的「致命一击」：数值是 Float.MAX_VALUE，不计入
        if (source.is(DamageTypes.GENERIC_KILL)) return 0;

        long rounded = Math.round(amount);
        if (rounded > MAX_COUNTABLE_DAMAGE) return 0;
        return (int) rounded;
    }

    /**
     * 从伤害来源里解析出「攻击者玩家」。
     *
     * <p>处理三种常见情况：</p>
     * <ul>
     *   <li>近战：{@code DamageSource#getEntity()} 就是玩家；</li>
     *   <li>投射物（箭/火球等）：来源实体是弹射物，真正的攻击者是 {@code Projectile#getOwner()}；</li>
     *   <li>驯服生物（狗/猫）：来源实体是宠物，攻击者是它的主人。</li>
     * </ul>
     *
     * <p><b>本方法在伤害结算的热路径上，任何异常都会直接崩服</b>，
     * 因此内部整体包了 try/catch，解析失败只记日志、返回 {@code null}（不归属任何玩家）。</p>
     */
    public static ServerPlayer attackerOf(DamageSource source)
    {
        if (source == null) return null;
        try
        {
            Entity entity = source.getEntity();
            if (entity == null) return null;

            if (entity instanceof ServerPlayer player)
            {
                return player;
            }
            if (entity instanceof Projectile projectile)
            {
                Entity owner = projectile.getOwner();
                if (owner instanceof ServerPlayer player)
                {
                    return player;
                }
            }
            if (entity instanceof TamableAnimal tamable)
            {
                // 注意：MC 的 DATA_OWNERUUID_ID 是 EntityDataAccessor<Optional<EntityReference<...>>>，
                // getOwnerReference() 取的是 Optional 的内容 —— 没有主人（未驯服 / 主人数据丢失）时返回 null，
                // 这里必须判空，否则野狼反击等场景会直接抛 NPE 崩服。
                EntityReference<LivingEntity> reference = tamable.getOwnerReference();
                if (reference == null) return null;

                // 主人可能已经离线（查不到实体），这里拿到 null 是正常的
                LivingEntity owner = reference.getEntity(tamable.level(), LivingEntity.class);
                if (owner instanceof ServerPlayer player)
                {
                    return player;
                }
            }
        }
        catch (Throwable t)
        {
            // 采集永远不能影响游戏：解析失败就当作「没有可归属的玩家」
            HermesLibMod.LOG.warn("[Hermes] 解析伤害来源的攻击者失败，已忽略本次归属", t);
        }
        return null;
    }
}
