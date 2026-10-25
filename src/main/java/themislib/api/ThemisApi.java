package themislib.api;

/**
 * Themis Lib 对外 API 的静态入口。
 *
 * <p>其它 Mod 可以在自己的初始化阶段（服务器已加载之后）通过
 * {@link #provider()} 拿到 {@link RankingDataProvider} 实现并读取统计数据。</p>
 *
 * <pre>{@code
 * RankingDataProvider stats = ThemisApi.provider();
 * if (stats != null) {
 *     long blocks = stats.getValue(uuid, DataDomain.REAL, Metric.BLOCKS_MINED);
 * }
 * }</pre>
 *
 * <p>本模组不提供任何查询命令，展示与排行逻辑全部交给下游消费者。</p>
 */
public final class ThemisApi
{
    private static volatile RankingDataProvider provider;

    private ThemisApi()
    {
    }

    /** 由 Themis Lib 内部在服务器加载完成时调用。 */
    public static void setProvider(RankingDataProvider newProvider)
    {
        provider = newProvider;
    }

    /** 获取数据提供者；服务器尚未启动时返回 null。 */
    public static RankingDataProvider provider()
    {
        return provider;
    }

    /** 数据是否已经就绪。 */
    public static boolean isReady()
    {
        return provider != null;
    }

    /**
     * 获取数据提供者，未就绪时抛异常。
     *
     * @throws IllegalStateException 数据尚未就绪
     */
    public static RankingDataProvider require()
    {
        RankingDataProvider current = provider;
        if (current == null)
        {
            throw new IllegalStateException("Themis Lib 数据尚未就绪（服务器未加载？）");
        }
        return current;
    }
}
