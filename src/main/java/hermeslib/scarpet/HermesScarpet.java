package hermeslib.scarpet;

import carpet.script.CarpetExpression;
import carpet.script.value.NumericValue;
import carpet.script.value.Value;
import hermeslib.api.DataDomain;
import hermeslib.api.Metric;
import hermeslib.stats.HermesStatsManager;

import java.util.List;
import java.util.UUID;

/**
 * 可选的 Scarpet 内置函数。
 *
 * <p>本模组<b>不注册任何命令</b>，但可以给 Scarpet 脚本提供只读查询函数，
 * 让脚本作者（而不是模组）决定如何展示与排行。</p>
 *
 * <pre>
 * ranking_data('Steve', 'blocks_mined')        -- 默认 REAL 域
 * ranking_data('Steve', 'blocks_mined', 'FAKE') -- 指定数据域
 * ranking_total('Steve')                       -- REAL 域全部指标之和
 * </pre>
 */
public final class HermesScarpet
{
    private HermesScarpet()
    {
    }

    /** 注册到 Carpet 表达式系统。 */
    public static void register(CarpetExpression expression)
    {
        // 用 impure 函数：返回值会随时间变化，不能被 Scarpet 当作纯函数缓存
        expression.getExpr().addImpureFunction("ranking_data", HermesScarpet::rankingData);
        expression.getExpr().addImpureFunction("ranking_total", HermesScarpet::rankingTotal);
    }

    private static Value rankingData(List<Value> params)
    {
        if (params.size() < 2) return Value.ZERO;
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return Value.ZERO;

        String playerName = params.get(0).getString();
        String metricName = params.get(1).getString();
        String domainName = params.size() > 2 ? params.get(2).getString() : DataDomain.REAL.id();

        UUID uuid = manager.findByName(playerName).orElse(null);
        if (uuid == null) return Value.ZERO;

        DataDomain domain = DataDomain.byId(domainName).orElse(DataDomain.REAL);
        Metric metric = Metric.byName(metricName).orElse(null);
        if (metric == null) return Value.ZERO;

        return new NumericValue(manager.getValue(uuid, domain, metric));
    }

    private static Value rankingTotal(List<Value> params)
    {
        if (params.isEmpty()) return Value.ZERO;
        HermesStatsManager manager = HermesStatsManager.get();
        if (manager == null) return Value.ZERO;

        String playerName = params.get(0).getString();
        String domainName = params.size() > 1 ? params.get(1).getString() : DataDomain.REAL.id();

        UUID uuid = manager.findByName(playerName).orElse(null);
        if (uuid == null) return Value.ZERO;

        DataDomain domain = DataDomain.byId(domainName).orElse(DataDomain.REAL);
        return new NumericValue(manager.getTotal(uuid, domain));
    }
}
