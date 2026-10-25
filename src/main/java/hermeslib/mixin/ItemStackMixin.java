package hermeslib.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

import java.util.function.Consumer;

/**
 * 采集「附魔次数」「工具耐久损耗」「工具损坏」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ItemStack#enchant(Holder<Enchantment>, int)} —— 施加一条附魔；
 *       管理器按「同一 tick 内同一件物品」去重，一次附魔行为（附魔台/铁砧/命令
 *       一次附加多条附魔）只计 1 次；</li>
 *   <li>{@code ItemStack#hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<ItemStack>)} —— 耐久损耗。</li>
 * </ul>
 *
 * <p><b>附魔的归属</b>：{@code enchant} 本身没有玩家信息，Hermes 会记录
 * 「最近一次使用附魔台/铁砧的玩家」（见 {@code ServerPlayerGameModeMixin#useItemOn}），
 * 再把附魔归属过去，窗口期 10 秒。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin
{
    @Inject(method = "enchant", at = @At("HEAD"))
    private void hermes$onEnchant(Holder<Enchantment> enchantment, int level, CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        // 客户端的附魔台界面预览同样会调用 enchant（单人世界里与服务端同处一个 JVM），
        // 不限定服务端线程会与真正的附魔重复计数
        if (!manager.isServerThread()) return;
        manager.addEnchantment();
    }

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"))
    private void hermes$onHurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak,
            CallbackInfo ci)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_DAMAGED, 1L);
    }

    /**
     * 工具彻底损坏（碎掉）时计数。
     *
     * <p>判定方式：方法返回后，物品要么已被清空（最后一把碎了），
     * 要么耐久已经打满（{@code damage >= maxDamage}）——两者都说明这一把报废了。</p>
     */
    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("RETURN"))
    private void hermes$afterHurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak,
            CallbackInfo ci)
    {
        ItemStack self = (ItemStack) (Object) this;
        boolean broken = self.isEmpty()
                || (self.getMaxDamage() > 0 && self.getDamageValue() >= self.getMaxDamage());
        if (!broken) return;

        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_BROKEN, 1L);
    }
}
