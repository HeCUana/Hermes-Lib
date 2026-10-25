package hermeslib.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import hermeslib.HermesLibMod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则翻译。
 *
 * <p>Carpet 26.x 的规则描述来自翻译键，键格式为
 * {@code <设置管理器标识>.rule.<规则名>.desc}，本模组标识为 {@code hermes}，
 * 因此键形如 {@code hermes.rule.rankingEnabled.desc}。</p>
 *
 * <p>注意：扩展提供的键不要以 {@code carpet.} / {@code rule.} / {@code category.} 开头，
 * 否则会被 Carpet 当作旧版键格式做迁移处理。</p>
 */
public final class HermesTranslations
{
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>()
    {
    }.getType();

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private HermesTranslations()
    {
    }

    /** 取指定语言的翻译；找不到时回落到 en_us。 */
    public static Map<String, String> get(String lang)
    {
        if (lang == null || lang.isEmpty()) return Collections.emptyMap();
        return CACHE.computeIfAbsent(lang, HermesTranslations::loadWithFallback);
    }

    private static Map<String, String> loadWithFallback(String lang)
    {
        Map<String, String> map = loadResource("assets/" + HermesLibMod.MOD_ID + "/lang/" + lang + ".json");
        if (map.isEmpty() && !"en_us".equals(lang))
        {
            map = loadResource("assets/" + HermesLibMod.MOD_ID + "/lang/en_us.json");
        }
        return map;
    }

    private static Map<String, String> loadResource(String path)
    {
        try (InputStream stream = HermesTranslations.class.getClassLoader().getResourceAsStream(path))
        {
            if (stream == null) return Collections.emptyMap();
            Map<String, String> parsed = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), MAP_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        }
        catch (Exception e)
        {
            return Collections.emptyMap();
        }
    }
}
