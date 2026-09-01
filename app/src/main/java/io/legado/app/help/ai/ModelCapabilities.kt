package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig

/**
 * 模型能力表(极简预算管理,不占 UI;用户可通过 aiContextWindowTokens/aiMaxOutputTokens 覆盖)。
 * 预算口径:
 *   输入注入上限 = contextWindow × 90% − maxOutput
 * 例:GLM-5.2 128K 窗口 → 128K × 0.9 − 64K = 51.2K ≈ 116K chars 输入(1 token ≈ 1.8~2.2 CJK 字符,按 2:1 保守估算)
 * 例:GLM-4 200K 默认兜底 → 200K × 0.9 − 64K = 116K tokens ≈ 250K chars
 */
object ModelCapabilities {

    data class Cap(val contextWindow: Int, val maxOutput: Int)

    /** (别名) → 能力。匹配规则:模型名 contains(忽略大小写) 任一别名则命中 */
    private val table: List<Pair<List<String>, Cap>> = listOf(
        listOf("glm-5.2", "glm5.2", "glm-5") to Cap(128_000, 64_000),
        listOf("glm-4.6", "glm4.6", "glm-4-plus", "glm-4-air") to Cap(128_000, 32_000),
        listOf("glm-4", "glm4") to Cap(128_000, 32_000),
        listOf("gpt-4o-mini", "gpt-4o") to Cap(128_000, 16_000),
        listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano") to Cap(1_047_576, 32_000),
        listOf("gpt-4-turbo", "gpt-4-turbo-preview") to Cap(128_000, 4_096),
        listOf("doubao-1.5", "doubao-pro-128k", "doubao-lite-128k", "doubao-128k") to Cap(128_000, 64_000),
        listOf("deepseek-chat", "deepseek-v3") to Cap(64_000, 8_000),
        listOf("deepseek-reasoner", "deepseek-r1") to Cap(64_000, 8_000),
        listOf("qwen2.5-72b", "qwen2.5-110b", "qwen-long") to Cap(1_024_000, 64_000),
        listOf("qwen2.5") to Cap(128_000, 8_000),
        listOf("moonshot-v1-128k", "moonshot-v1") to Cap(128_000, 4_096),
        listOf("minimax-01", "abab6.5s-chat") to Cap(245_760, 8_192)
    )

    private val DEFAULT_UNKNOWN = Cap(200_000, 64_000)

    fun resolve(model: String): Cap {
        val m = model.trim().lowercase()
        if (m.isBlank()) return DEFAULT_UNKNOWN
        table.forEach { (aliases, cap) ->
            if (aliases.any { alias -> m.contains(alias, ignoreCase = true) }) {
                return cap
            }
        }
        return DEFAULT_UNKNOWN
    }

    /** 最终生效值:用户覆盖优先,否则模型表 */
    fun finalCap(model: String): Cap {
        val base = resolve(model)
        val ctx = if (AppConfig.aiContextWindowTokens > 0) AppConfig.aiContextWindowTokens else base.contextWindow
        val out = if (AppConfig.aiMaxOutputTokens > 0) AppConfig.aiMaxOutputTokens else base.maxOutput
        return Cap(ctx.coerceAtLeast(8_000), out.coerceAtLeast(1_000).coerceAtMost(ctx))
    }

    /** 返回注入上限字符数(CJK token≈2 chars × 保守系数);超长时调用方按章节边界截断并提示用户 */
    fun injectionCharsBudget(model: String): Int {
        val cap = finalCap(model)
        val inputTokens = (cap.contextWindow * 0.9 - cap.maxOutput).toInt().coerceAtLeast(4_000)
        // 1 token ≈ 2 CJK chars;英文比例高时预算更充足,取 2 保守
        return inputTokens * 2
    }
}
