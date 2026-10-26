package hermeslib.stats;

import hermeslib.api.Metric;

import java.util.Collections;
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

    /**
     * 覆盖式写入单个指标。
     *
     * <p>用于原版统计导入：原版统计本身是「累计值」，导入时应当
     * <b>覆盖</b>而不是累加，否则重复导入会让数值翻倍。</p>
     *
     * <p>只在服务器主线程调用（导入在主线程刷新内存），因此
     * {@code reset()} + {@code add()} 的组合不存在与采集事件竞争的问题。</p>
     */
    public void set(Metric metric, long value)
    {
        LongAdder adder = values[metric.ordinal()];
        adder.reset();
        if (value != 0L)
        {
            adder.add(value);
        }
    }

    /**
     * 取较大值写入。
     *
     * <p>用于原版统计导入：原版统计是「从第一次进世界起累计」，
     * 而 Hermes 的实时值是「从装上模组起累计」——后者是前者的子集。
     * 但原版文件可能是陈旧的（玩家还在线时文件停留在上次登出那一刻），
     * 盲目覆盖反而会把更新的实时数据冲掉，所以统一取两者较大值。</p>
     */
    public void setMax(Metric metric, long value)
    {
        if (value <= get(metric)) return;
        set(metric, value);
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
        return Collections.unmodifiableMap(map);
    }
}
