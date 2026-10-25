package hermeslib.collector;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
     */
    public static ServerPlayer attackerOf(DamageSource source)
    {
        if (source == null) return null;
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
            LivingEntity owner = tamable.getOwnerReference().getEntity(tamable.level(), LivingEntity.class);
            if (owner instanceof ServerPlayer player)
            {
                return player;
            }
        }
        return null;
    }
}
