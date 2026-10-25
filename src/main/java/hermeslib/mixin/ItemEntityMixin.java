package hermeslib.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「拾取物品数量」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ItemEntity#playerTouch(Player)}。</li>
 * </ul>
 *
 * <p><b>为什么不能直接在 HEAD 计数</b>：原版 {@code Player#tick()} 每一 tick 都会对
 * 拾取范围内的每个掉落物调用一次 {@code playerTouch}，真正的「拾取」只是它内部的一次
 * 有条件分支（{@code pickupDelay == 0} 且背包能装下）。在 HEAD 计数会变成
 * 「范围内有掉落物就每 tick 加一次」。</p>
 *
 * <p><b>现在的做法</b>：</p>
 * <ol>
 *   <li>HEAD 记录掉落物当前数量；</li>
 *   <li>在 {@code Player#take(Entity, int)} 调用处（只有真正拾取成功才会走到）
 *       用「记录值 − 当前值」算出<strong>实际被拿走的数量</strong>。</li>
 * </ol>
 *
 * <p>用差值而不是整叠数量，是为了让「背包只剩 30 格、地上有 64 个箭」这种部分拾取
 * 也只计入真正进包的数量。</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin
{
    /** 进入 playerTouch 时掉落物的数量，用于计算真正被捡走的数量。 */
    @Unique
    private int hermes$countBefore;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void hermes$beforePlayerTouch(Player player, CallbackInfo ci)
    {
        hermes$countBefore = ((ItemEntity) (Object) this).getItem().getCount();
    }

    /**
     * 真正拾取成功时才走到这里。
     *
     * <p>{@code Player#take(Entity, int)} 在 {@code Inventory#add(ItemStack)} 之后被调用，
     * 此时掉落物里的物品已经被背包消耗，因此差值就是实际拿到的数量。</p>
     */
    @Inject(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;take(Lnet/minecraft/world/entity/Entity;I)V"))
    private void hermes$onPickedUp(Player player, CallbackInfo ci)
    {
        int picked = hermes$countBefore - ((ItemEntity) (Object) this).getItem().getCount();
        if (picked <= 0) return;

        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_PICKED_UP, picked);
    }
}
