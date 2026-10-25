package themislib.stats;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「玩家放置过的方块」，用于实现「仅统计自然方块」的挖掘过滤。
 *
 * <p><b>为什么需要它？</b>原版没有「方块是否自然生成」的标记位，
 * 判断一个方块是不是玩家自己放上去的，最可靠的办法就是追踪放置行为：
 * 放置时记一笔，破坏时若命中记录则说明是「自己放的自己挖」，不计入自然挖掘量。</p>
 *
 * <p>记录只存在内存里，带过期时间（默认 30 分钟）与容量上限，避免无限增长；
 * 服务重启后清空——这意味着「重启前放置、重启后挖掉」的方块会被当作自然方块，
 * 这是可接受的精度取舍（详见 README 的注意事项）。</p>
 */
public final class PlacedBlockTracker
{
    /** 记录过期时间：30 分钟。 */
    private static final long MAX_AGE_MILLIS = 30L * 60L * 1000L;

    /** 容量上限，超过后触发一次清理。 */
    private static final int MAX_ENTRIES = 200_000;

    private record BlockKey(int levelId, int x, int y, int z)
    {
    }

    private static final Map<BlockKey, Long> PLACED = new ConcurrentHashMap<>();

    private PlacedBlockTracker()
    {
    }

    /** 标记某个方块为「玩家放置」。 */
    public static void markPlaced(ServerLevel level, BlockPos pos)
    {
        if (level == null || pos == null) return;
        if (PLACED.size() >= MAX_ENTRIES)
        {
            prune(System.currentTimeMillis());
        }
        PLACED.put(key(level, pos), System.currentTimeMillis());
    }

    /**
     * 查询并移除记录：返回 true 表示该方块是玩家放置的（不可计入自然挖掘量）。
     */
    public static boolean consumeIfPlaced(ServerLevel level, BlockPos pos)
    {
        if (level == null || pos == null) return false;
        return PLACED.remove(key(level, pos)) != null;
    }

    /** 清理过期记录。 */
    public static void prune(long now)
    {
        PLACED.entrySet().removeIf(entry -> now - entry.getValue() > MAX_AGE_MILLIS);
    }

    /** 当前记录数（调试用）。 */
    public static int size()
    {
        return PLACED.size();
    }

    private static BlockKey key(ServerLevel level, BlockPos pos)
    {
        return new BlockKey(level.dimension().toString().hashCode(), pos.getX(), pos.getY(), pos.getZ());
    }
}
