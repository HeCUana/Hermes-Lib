package hermeslib.api;

import java.util.List;

/**
 * 原版统计导入的结果。
 *
 * @param playersProcessed 成功处理的玩家文件数
 * @param playersSkipped   跳过的文件数（解析失败 / 版本不兼容 / 无可识别统计项）
 * @param metricsImported  写入的指标总条数
 * @param errors           错误摘要（可能为空）
 */
public record ImportResult(int playersProcessed, int playersSkipped, int metricsImported, List<String> errors)
{
    public ImportResult
    {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** 是否完全没有出错。 */
    public boolean isSuccess()
    {
        return errors.isEmpty();
    }

    /** 扫描到的玩家文件总数。 */
    public int playersTotal()
    {
        return playersProcessed + playersSkipped;
    }
}
