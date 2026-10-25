package themislib.stats;

import themislib.api.DataDomain;

import java.util.UUID;

/**
 * 内存统计的键：一个玩家 UUID + 一个数据域。
 *
 * @param uuid   玩家的「有效 UUID」（假人可能与真人共享 UUID，这里用派生 UUID）
 * @param domain 数据域（REAL / FAKE）
 */
public record StatKey(UUID uuid, DataDomain domain)
{
}
