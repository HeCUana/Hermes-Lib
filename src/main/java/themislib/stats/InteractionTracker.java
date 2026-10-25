package themislib.stats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「最近与某个生物交互过的玩家」。
 *
 * <p>用途：动物繁殖在原版里并不携带玩家信息（{@code Animal#spawnChildFromBreeding}
 * 只知道两只亲本），因此这里通过 {@code Animal#mobInteract} 记录交互者，
 * 在繁殖发生时回溯出「是谁喂的」，从而把繁殖计数归属到正确的玩家。</p>
 *
 * <p>记录带过期时间（默认 60 秒），并只保留每个生物最近一次交互。</p>
 */
public final class InteractionTracker
{
    private static final long MAX_AGE_MILLIS = 60L * 1000L;

    private record Entry(UUID player, long timestamp)
    {
    }

    private static final Map<UUID, Entry> LAST_INTERACTION = new ConcurrentHashMap<>();

    private InteractionTracker()
    {
    }

    /** 记录玩家 {@code player} 与生物 {@code animal} 的一次交互。 */
    public static void record(UUID animal, UUID player)
    {
        if (animal == null || player == null) return;
        LAST_INTERACTION.put(animal, new Entry(player, System.currentTimeMillis()));
    }

    /** 查询最近的交互者；没有或已过期则返回 null。 */
    public static UUID lookup(UUID animal)
    {
        if (animal == null) return null;
        Entry entry = LAST_INTERACTION.get(animal);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp() > MAX_AGE_MILLIS)
        {
            LAST_INTERACTION.remove(animal, entry);
            return null;
        }
        return entry.player();
    }

    /** 清理过期记录。 */
    public static void prune()
    {
        long now = System.currentTimeMillis();
        LAST_INTERACTION.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > MAX_AGE_MILLIS);
    }
}
