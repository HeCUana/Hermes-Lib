package themislib.stats;

import themislib.api.Metric;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个玩家（单数据域）的内存累计值。
 *
 * <p>使用 {@link LongAdder} 做无锁累加：写发生在服务器主线程，
 * 读（快照）可能发生在 SQLite 线程，因此计数器本身必须线程安全。</p>
 */
public final class PlayerStats
{
    private final LongAdder[] values;

    public PlayerStats()
    {
        Metric[] metrics = Metric.values();
        this.values = new LongAdder[metrics.length];
        for (int i = 0; i < metrics.length; i++)
        {
            this.values[i] = new LongAdder();
        }
    }

    public void add(Metric metric, long amount)
    {
        if (amount == 0) return;
        values[metric.ordinal()].add(amount);
    }

    public long get(Metric metric)
    {
        return values[metric.ordinal()].sum();
    }

    public long total()
    {
        long sum = 0L;
        for (LongAdder adder : values)
        {
            sum += adder.sum();
        }
        return sum;
    }

    /** 拍一份一致性快照，供落库使用。 */
    public Map<Metric, Long> snapshot()
    {
        Map<Metric, Long> map = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values())
        {
            long v = values[metric.ordinal()].sum();
            if (v != 0L)
            {
                map.put(metric, v);
            }
        }
        return map;
    }
}
