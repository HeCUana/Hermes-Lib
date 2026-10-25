package hermeslib;

import carpet.CarpetServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hermes-lib —— 纯数据记录与接口提供型 Carpet 附属模组。
 *
 * <p>本模组只做三件事：</p>
 * <ol>
 *   <li>监听游戏行为 → 累计统计；</li>
 *   <li>按「玩家 / 假人」分域存储 → 持久化到 SQLite；</li>
 *   <li>通过 Java API（可选 Scarpet 函数）把数据暴露给下游消费者。</li>
 * </ol>
 *
 * <p>不提供任何查询命令、排行榜、计分板、HUD、Tab 展示或聊天栏输出。</p>
 */
public final class HermesLibMod implements ModInitializer
{
    /** Mod ID（与 fabric.mod.json 保持一致）。 */
    public static final String MOD_ID = "hermes_lib";

    /** 显示名。 */
    public static final String MOD_NAME = "Hermes-lib";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** 版本号，与 fabric.mod.json 保持一致。 */
    public static final String VERSION = "1.0.0";

    /**
     * 调试日志开关：启动参数 {@code -Dhermes.debug=true} 时输出采集/落库的关键节点日志。
     *
     * <p>默认关闭，正式环境不产生任何额外输出。</p>
     */
    public static final boolean DEBUG = Boolean.getBoolean("hermes.debug");

    @Override
    public void onInitialize()
    {
        // 必须在 Carpet 的 server 入口点之前注册（Fabric 的 main 入口点先于 server 入口点执行），
        // 这样 Carpet 初始化规则与翻译时就能看到我们的扩展。
        CarpetServer.manageExtension(new HermesExtension());
        LOG.info("[Hermes] 已注册 Carpet 扩展（仅采集与接口，不含任何查询命令）");
    }
}
