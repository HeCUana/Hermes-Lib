package hermeslib.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import hermeslib.api.Metric;
import hermeslib.rules.HermesRules;
import hermeslib.stats.PlacedBlockTracker;
import hermeslib.stats.HermesStatsManager;

/**
 * 采集「挖掘方块」「使用物品」，并记录附魔台/铁砧的使用者。
 *
 * <p>注入点（MC 26.3 / Mojang 映射）：</p>
 * <ul>
 *   <li>{@code ServerPlayerGameMode#destroyBlock(BlockPos)} —— 玩家破坏方块；</li>
 *   <li>{@code ServerPlayerGameMode#useItem(ServerPlayer, Level, ItemStack, InteractionHand)} —— 右键使用物品；</li>
 *   <li>{@code ServerPlayerGameMode#useItemOn(ServerPlayer, Level, ItemStack, InteractionHand, BlockHitResult)} —— 右键方块。</li>
 * </ul>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin
{
    @Shadow
    @Final
    protected ServerPlayer player;

    /**
     * 挖掘计数。
     *
     * <p>「仅统计自然方块」的实现：放置方块时由 {@code BlockItemMixin} 记录坐标，
     * 破坏时若命中记录，说明是玩家自己放上去的，不计入自然挖掘量。</p>
     */
    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void hermes$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;

        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;

        ServerPlayer serverPlayer = this.player;
        if (serverPlayer == null) return;
        Level level = serverPlayer.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 无论是否计入自然挖掘，都要消费掉这条「玩家放置」记录，避免残留
        boolean placedByPlayer = PlacedBlockTracker.consumeIfPlaced(serverLevel, pos);
        boolean natural = !placedByPlayer;

        if (natural || !HermesRules.rankingMinedNaturalOnly)
        {
            manager.add(serverPlayer, Metric.BLOCKS_MINED, 1L);
        }
    }

    /** 使用物品计数（右键吃东西、举盾、拉弓等）。 */
    @Inject(method = "useItem", at = @At("RETURN"))
    private void hermes$onUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) return;

        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        manager.add(player, Metric.ITEMS_USED, 1L);
    }

    /**
     * 记录「谁在用附魔台 / 铁砧」。
     *
     * <p>{@code ItemStack#enchant(Holder, int)} 本身不携带玩家信息，
     * 因此这里先记住最近一次操作附魔台/铁砧的玩家，附魔发生时再归属过去。</p>
     */
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void hermes$onUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir)
    {
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = serverLevel.getBlockState(pos);
        if (state.is(Blocks.ENCHANTING_TABLE)
                || state.is(Blocks.ANVIL)
                || state.is(Blocks.CHIPPED_ANVIL)
                || state.is(Blocks.DAMAGED_ANVIL))
        {
            manager.recordEnchantUser(player);
        }
    }
}
