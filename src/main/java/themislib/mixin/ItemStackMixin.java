package themislib.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import themislib.api.Metric;
import themislib.stats.ThemisStatsManager;

import java.util.function.Consumer;

/**
 * 采集「附魔次数」「工具耐久损耗」「工具损坏」。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ItemStack#enchant(Holder<Enchantment>, int)} —— 施加一次附魔；</li>
 *   <li>{@code ItemStack#hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<ItemStack>)} —— 耐久损耗。</li>
 * </ul>
 *
 * <p><b>附魔的归属</b>：{@code enchant} 本身没有玩家信息，Themis 会记录
 * 「最近一次使用附魔台/铁砧的玩家」（见 {@code ServerPlayerGameModeMixin#useItemOn}），
 * 再把附魔归属过去，窗口期 10 秒。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin
{
    @Inject(method = "enchant", at = @At("HEAD"))
    private void themis$onEnchant(Holder<Enchantment> enchantment, int level, CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.addEnchantment();
    }

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"))
    private void themis$onHurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak,
            CallbackInfo ci)
    {
        ThemisStatsManager manager = ThemisStatsManager.get();
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
    private void themis$afterHurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak,
            CallbackInfo ci)
    {
        ItemStack self = (ItemStack) (Object) this;
        boolean broken = self.isEmpty()
                || (self.getMaxDamage() > 0 && self.getDamageValue() >= self.getMaxDamage());
        if (!broken) return;

        ThemisStatsManager manager = ThemisStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_BROKEN, 1L);
    }
}
