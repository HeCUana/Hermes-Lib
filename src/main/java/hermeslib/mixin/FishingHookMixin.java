package hermeslib.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import hermeslib.HermesLibMod;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「实际钓到的物品数量」（鱼、垃圾、宝藏都算）。
 *
 * <p><b>为什么不是 {@code FishingHook#catchingFish}</b>：那个方法在浮标等待期间
 * <b>每 tick 都会被调用</b>（它只负责咬钩动画与粒子音效），
 * 挂在上面统计到的只是「垂钓时长」。</p>
 *
 * <p><b>真正的落点</b>：MC 26.3 的钓鱼结算在 {@code FishingHook#retrieve(ItemStack)}
 * （玩家收竿时）里，用战利品表生成一组 {@code ItemStack}，逐个变成 {@code ItemEntity}
 * 掉在钩子旁边（玩家再走过去捡起来），最后才给真正的鱼发 {@code Stats.FISH_CAUGHT}。
 * 所以「战利品实体被生成」这一刻 = 真正钓到了东西。</p>
 *
 * <p>用 {@link Redirect} 拿到被生成的实体（普通 {@code @Inject} 拿不到 INVOKE 的实参），
 * 只统计 {@link ItemEntity}（经验球不是），数量取该实体的物品堆叠数——
 * 鱼、垃圾、宝藏全部计入。</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin
{
    @Redirect(method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean hermes$onSpawnLoot(Level level, Entity entity)
    {
        try
        {
            if (entity instanceof ItemEntity item)
            {
                int count = item.getItem().getCount();
                if (count > 0)
                {
                    Player owner = ((FishingHook) (Object) this).getPlayerOwner();
                    HermesStatsManager manager = HermesStatsManager.get();
                    if (owner != null && manager != null)
                    {
                        manager.add(owner, Metric.FISH_CAUGHT, count);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            HermesLibMod.LOG.warn("[Hermes] 统计钓鱼战利品失败，已忽略", t);
        }
        // 原样调用被重定向的方法
        return level.addFreshEntity(entity);
    }
}
