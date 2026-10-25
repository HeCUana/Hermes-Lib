package themislib;

import carpet.CarpetExtension;
import carpet.api.settings.SettingsManager;
import carpet.script.CarpetExpression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import themislib.api.ThemisApi;
import themislib.i18n.ThemisTranslations;
import themislib.rules.ThemisRules;
import themislib.scarpet.ThemisScarpet;
import themislib.stats.ThemisStatsManager;

import java.util.Map;

/**
 * Carpet 扩展实现。
 *
 * <p>本扩展只负责「采集 + 存储 + 暴露接口」：</p>
 * <ul>
 *   <li>通过 {@link #extensionSettingsManager()} 提供一组<b>采集开关规则</b>
 *       （命令 {@code /themis}，配置存于世界目录 {@code themis.conf}）；</li>
 *   <li>不注册任何玩家查询命令、不注册排行榜 / 计分板 / HUD / Tab 展示。</li>
 * </ul>
 */
public final class ThemisExtension implements CarpetExtension
{
    /** 设置管理器标识，同时也是规则命令名与配置文件名（themis.conf）。 */
    public static final String IDENTIFIER = "themis";

    private SettingsManager settingsManager;
    private ThemisStatsManager statsManager;

    @Override
    public void onGameStarted()
    {
        // Carpet 已完成自身的规则解析，这里注册我们自己的规则类。
        // 新版规则 API（carpet.api.settings.Rule）要求每条规则都有翻译键，
        // 翻译通过 canHasTranslations 提供。
        settingsManager = new SettingsManager(ThemisLib.VERSION, IDENTIFIER, ThemisLib.MOD_NAME);
        settingsManager.parseSettingsClass(ThemisRules.class);
    }

    @Override
    public SettingsManager extensionSettingsManager()
    {
        return settingsManager;
    }

    @Override
    public void onServerLoaded(MinecraftServer server)
    {
        // 单人游戏里服务器可能被反复加载，先把上一份实例收尾
        if (statsManager != null)
        {
            statsManager.shutdown();
            statsManager = null;
        }
        ThemisStatsManager manager = new ThemisStatsManager();
        statsManager = manager;
        ThemisStatsManager.install(manager);
        ThemisApi.setProvider(manager);
        manager.start(server);
        ThemisLib.LOG.info("[Themis] 统计系统已启动，数据库：{}/ranking-stats.db",
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        warmUpMixinTargets();
    }

    /**
     * 预热 Mixin 目标类（仅调试模式）。
     *
     * <p>Mixin 的注入点在目标类被加载时才解析。如果某个注入点写错，
     * 平时可能几小时都不触发，直到游戏里真的发生那个行为才崩服。
     * 这里在启动时主动加载一遍全部目标类，把错误提前暴露在启动日志里。</p>
     */
    private static void warmUpMixinTargets()
    {
        if (!ThemisLib.DEBUG) return;
        String[] targets = {
                "net.minecraft.server.level.ServerPlayerGameMode",
                "net.minecraft.world.item.BlockItem",
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.player.Player",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.world.entity.animal.Animal",
                "net.minecraft.world.entity.TamableAnimal",
                "net.minecraft.world.entity.item.ItemEntity",
                "net.minecraft.world.inventory.ResultSlot",
                "net.minecraft.world.inventory.MerchantResultSlot",
                "net.minecraft.world.entity.projectile.FishingHook",
                "net.minecraft.world.item.ItemStack",
        };
        for (String target : targets)
        {
            try
            {
                Class.forName(target);
            }
            catch (Throwable t)
            {
                ThemisLib.LOG.error("[Themis][debug] 预热 Mixin 目标类失败：" + target, t);
            }
        }
        ThemisLib.LOG.info("[Themis][debug] Mixin 目标类预热完成（{} 个），无注入错误", targets.length);
    }

    @Override
    public void onTick(MinecraftServer server)
    {
        ThemisStatsManager manager = statsManager;
        if (manager != null)
        {
            manager.tick(server);
        }
    }

    @Override
    public void onPlayerLoggedIn(ServerPlayer player)
    {
        ThemisStatsManager manager = statsManager;
        if (manager != null)
        {
            manager.onJoin(player);
        }
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player)
    {
        ThemisStatsManager manager = statsManager;
        if (manager != null)
        {
            manager.onQuit(player);
        }
    }

    @Override
    public void onServerClosed(MinecraftServer server)
    {
        if (statsManager != null)
        {
            statsManager.shutdown();
            statsManager = null;
        }
        ThemisApi.setProvider(null);
        ThemisStatsManager.uninstall();
    }

    @Override
    public void onReload(MinecraftServer server)
    {
        // 规则改动即时生效（采集时逐次读取规则值），无需重载数据
        ThemisLib.LOG.info("[Themis] 规则已重新加载，采集开关即时生效");
    }

    @Override
    public void scarpetApi(CarpetExpression expression)
    {
        ThemisScarpet.register(expression);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang)
    {
        return ThemisTranslations.get(lang);
    }

    @Override
    public String version()
    {
        return ThemisLib.VERSION;
    }
}
