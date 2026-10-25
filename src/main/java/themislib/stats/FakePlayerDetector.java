package themislib.stats;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.world.entity.player.Player;
import themislib.api.DataDomain;
import themislib.rules.ThemisRules;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 假人识别与数据域分流。
 *
 * <p><b>核心原则：绝不能只靠 UUID 判断真假人。</b></p>
 *
 * <p>Fabric 规范允许通用假人（{@code net.fabricmc.fabric.api.entity.FakePlayer}）
 * 与真人共享同一个 UUID，而 Carpet 假人则拥有自己的 UUID。
 * 因此：</p>
 *
 * <ol>
 *   <li>类型判定决定数据域（REAL / FAKE）；</li>
 *   <li>写入时使用「有效 UUID」，对共享 UUID 的假人派生出独立伪 UUID，
 *       这样即使把假人写进 REAL 域也不会覆盖真人数据。</li>
 * </ol>
 */
public final class FakePlayerDetector
{
    /**
     * Fabric 通用假人的类名。
     *
     * <p>Themis Lib 不对 Fabric API 施加硬依赖（MC 26.3 生态里 Fabric API 并非必需），
     * 因此这里用反射探测；若你的服务端装了 Fabric API，可以直接改成
     * {@code player instanceof net.fabricmc.fabric.api.entity.FakePlayer}，
     * 效果完全等价。</p>
     */
    private static final String FABRIC_FAKE_PLAYER_CLASS = "net.fabricmc.fabric.api.entity.FakePlayer";

    private static volatile boolean fabricProbed = false;
    private static volatile Class<?> fabricFakePlayerClass = null;

    private FakePlayerDetector()
    {
    }

    /** 该玩家是否为假人（Carpet 假人 或 Fabric FakePlayer）。 */
    public static boolean isFake(Player player)
    {
        if (player == null) return false;
        // Carpet 假人：carpet.patches.EntityPlayerMPFake
        if (player instanceof EntityPlayerMPFake) return true;
        // Fabric 通用假人：反射探测，避免硬依赖 Fabric API
        Class<?> fake = fabricFakeClass();
        return fake != null && fake.isInstance(player);
    }

    private static Class<?> fabricFakeClass()
    {
        if (!fabricProbed)
        {
            synchronized (FakePlayerDetector.class)
            {
                if (!fabricProbed)
                {
                    try
                    {
                        fabricFakePlayerClass = Class.forName(FABRIC_FAKE_PLAYER_CLASS);
                    }
                    catch (Throwable ignored)
                    {
                        // 没装 Fabric API（或版本不匹配）——只支持 Carpet 假人识别
                        fabricFakePlayerClass = null;
                    }
                    fabricProbed = true;
                }
            }
        }
        return fabricFakePlayerClass;
    }

    /**
     * 该玩家当前应该写入哪个数据域。
     *
     * <p>{@code rankingSeparateFakePlayers=false} 时，假人也会被写进 REAL 域，
     * 但 UUID 依旧独立（见 {@link #effectiveUuid(Player)}），不会污染真人数据。</p>
     */
    public static DataDomain domainOf(Player player)
    {
        if (!ThemisRules.rankingSeparateFakePlayers)
        {
            return DataDomain.REAL;
        }
        return isFake(player) ? DataDomain.FAKE : DataDomain.REAL;
    }

    /**
     * 写入数据库时使用的有效 UUID。
     *
     * <ul>
     *   <li>真人：自身 UUID；</li>
     *   <li>Carpet 假人：自身 UUID（Carpet 为每个假人生成独立 UUID）；</li>
     *   <li>Fabric 通用假人：按「归属者 UUID + 假人名」派生一个稳定伪 UUID，
     *       避免与真人以及同一归属者的其它假人互相覆盖。</li>
     * </ul>
     */
    public static UUID effectiveUuid(Player player)
    {
        if (player == null) return null;
        if (!isFake(player)) return player.getUUID();
        if (player instanceof EntityPlayerMPFake) return player.getUUID();

        String owner = String.valueOf(player.getUUID());
        String name = "fake";
        try
        {
            // MC 26.3 的 authlib 10 用 GameProfile#name()（旧版本是 getName()）
            if (player.getGameProfile() != null && player.getGameProfile().name() != null)
            {
                name = player.getGameProfile().name();
            }
        }
        catch (Throwable ignored)
        {
            // 取不到名字就用默认名
        }
        return UUID.nameUUIDFromBytes(("themis:fake:" + owner + ":" + name).getBytes(StandardCharsets.UTF_8));
    }
}
