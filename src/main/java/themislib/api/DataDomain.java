package themislib.api;

import java.util.Locale;
import java.util.Optional;

/**
 * 数据域：把「真实玩家」与「假人」彻底分开存储。
 *
 * <p>数据库 {@code player_stats.domain} 列的取值，同样是稳定契约。</p>
 *
 * <ul>
 *   <li>{@link #REAL} —— 真实玩家</li>
 *   <li>{@link #FAKE} —— 假人（Carpet 假人 / Fabric FakePlayer）</li>
 * </ul>
 */
public enum DataDomain
{
    REAL("REAL"),
    FAKE("FAKE");

    private final String id;

    DataDomain(String id)
    {
        this.id = id;
    }

    /** 数据库中 {@code player_stats.domain} 的取值。 */
    public String id()
    {
        return id;
    }

    public static Optional<DataDomain> byId(String id)
    {
        if (id == null) return Optional.empty();
        String key = id.trim().toUpperCase(Locale.ROOT);
        for (DataDomain d : values())
        {
            if (d.id.equals(key)) return Optional.of(d);
        }
        return Optional.empty();
    }
}
