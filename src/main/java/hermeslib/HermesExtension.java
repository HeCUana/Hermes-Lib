package hermeslib;

import carpet.CarpetExtension;
import carpet.api.settings.SettingsManager;
import carpet.script.CarpetExpression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import hermeslib.api.HermesApi;
import hermeslib.i18n.HermesTranslations;
import hermeslib.rules.HermesRules;
import hermeslib.scarpet.HermesScarpet;
import hermeslib.stats.HermesStatsManager;

import java.util.Map;

/**
 * Carpet 扩展实现。
 *
 * <p>本扩展只负责「采集 + 存储 + 暴露接口」：</p>
 * <ul>
 *   <li>通过 {@link #extensionSettingsManager()} 提供一组<b>采集开关规则</b>
 *       （命令 {@code /hermesLib}，配置存于世界目录 {@code HermesLibMod.conf}）；</li>
 *   <li>不注册任何玩家查询命令、不注册排行榜 / 计分板 / HUD / Tab 展示。</li>
 * </ul>
 */
public final class HermesExtension implements CarpetExtension
{
    /** 设置管理器标识，同时也是规则命令名与配置文件名（HermesLibMod.conf）。 */
    public static final String IDENTIFIER = "hermesLib";

    private SettingsManager settingsManager;
    private HermesStatsManager statsManager;

    @Override
    public void onGameStarted()
    {
        // Carpet 已完成自身的规则解析，这里注册我们自己的规则类。
        // 新版规则 API（carpet.api.settings.Rule）要求每条规则都有翻译键，
        // 翻译通过 canHasTranslations 提供。
        settingsManager = new SettingsManager(HermesLibMod.VERSION, IDENTIFIER, HermesLibMod.MOD_NAME);
        settingsManager.parseSettingsClass(HermesRules.class);
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
        HermesStatsManager manager = new HermesStatsManager();
        statsManager = manager;
        HermesStatsManager.install(manager);
        HermesApi.setProvider(manager);
        manager.start(server);
        HermesLibMod.LOG.info("[Hermes] 统计系统已启动，数据库：{}/{}",
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                HermesStatsManager.DATABASE_FILE);
        warmUpMixinTargets();
    }

    @Override
    public void onServerLoadedWorlds(MinecraftServer server)
    {
        // 世界已加载完毕，原版统计目录（<世界>/stats）此时才存在。
        // 启动参数 -Dhermes.importVanillaOnStart=true 时自动导入一次原版统计。
        if (!Boolean.getBoolean("hermes.importVanillaOnStart")) return;
        HermesStatsManager manager = this.statsManager;
        if (manager == null) return;
        try
        {
            hermeslib.api.ImportResult result = manager.importVanillaStats();
            HermesLibMod.LOG.info("[Hermes] 启动导入原版统计：成功 {} 人 / 跳过 {} / 指标 {} 条 / 错误 {} 条",
                    result.playersProcessed(), result.playersSkipped(), result.metricsImported(), result.errors().size());
            for (String error : result.errors())
            {
                HermesLibMod.LOG.warn("[Hermes] 导入提示：{}", error);
            }
        }
        catch (Throwable t)
        {
            HermesLibMod.LOG.error("[Hermes] 启动导入原版统计失败", t);
        }
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
        if (!HermesLibMod.DEBUG) return;
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
                HermesLibMod.LOG.error("[Hermes][debug] 预热 Mixin 目标类失败：" + target, t);
            }
        }
        HermesLibMod.LOG.info("[Hermes][debug] Mixin 目标类预热完成（{} 个），无注入错误", targets.length);
    }

    @Override
    public void onTick(MinecraftServer server)
    {
        HermesStatsManager manager = statsManager;
        if (manager != null)
        {
            manager.tick(server);
        }
    }

    @Override
    public void onPlayerLoggedIn(ServerPlayer player)
    {
        HermesStatsManager manager = statsManager;
        if (manager != null)
        {
            manager.onJoin(player);
        }
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player)
    {
        HermesStatsManager manager = statsManager;
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
        HermesApi.setProvider(null);
        HermesStatsManager.uninstall();
    }

    @Override
    public void onReload(MinecraftServer server)
    {
        // 规则改动即时生效（采集时逐次读取规则值），无需重载数据
        HermesLibMod.LOG.info("[Hermes] 规则已重新加载，采集开关即时生效");
    }

    @Override
    public void scarpetApi(CarpetExpression expression)
    {
        HermesScarpet.register(expression);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang)
    {
        return HermesTranslations.get(lang);
    }

    @Override
    public String version()
    {
        return HermesLibMod.VERSION;
    }
}
