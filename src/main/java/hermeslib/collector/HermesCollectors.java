package hermeslib.collector;

import hermeslib.HermesLibMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
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
    private HermesCollectors()
    {
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
