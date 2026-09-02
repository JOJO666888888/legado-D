package io.legado.app.help.ai

import io.legado.app.data.entities.AiAgentMsg
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 前情提要 Rolling Summary(M4,MoRead RollingSummarizer 移植)。
 *
 * 语义:消息数超过阈值且水位落后时,把「历史窗口外的早前对话」压缩成一段 ≤600 字的中文前情提要,
 * 注入 system 前缀,修复"聊到一半 AI 忘记开头"。
 *
 * 纪律(防回归):
 *   - 走 CHEAP 角色(未配置回退主模型),不挤占主模型额度;
 *   - 失败静默返回 null,调用方保持旧行为;
 *   - 提要是"整段重写"而非追加(避免无限膨胀),不使用历史窗口内的消息(窗口内已喂给模型)。
 */
object RollingSummaryService {

    /** 消息数超过该阈值才触发前情提要 */
    const val MSG_THRESHOLD = 20

    /** 单次提炼最多纳入的消息条数(防超长) */
    const val MAX_MESSAGES = 120

    /**
     * 是否应触发一次刷新:总消息数 > 阈值 且 水位落后于最新消息。
     * 水位 0 = 从未提炼过(从头部开始);水位落后 = 最新消息 id > 已提炼水位。
     */
    fun shouldRefresh(messageCount: Int, summarizedThroughMessageId: Long, latestMsgId: Long): Boolean {
        if (messageCount <= MSG_THRESHOLD) return false
        if (summarizedThroughMessageId == 0L) return true
        return latestMsgId > summarizedThroughMessageId
    }

    /**
     * 提炼前情提要:把 [pastSummary] 之后的 [newMessages](按时间序)整段重写为一段新提要。
     * 旧提要 + 新消息都放进 user prompt,让模型"基于已有提要增量重写"。
     * 失败返回 null(调用方静默降级)。
     */
    suspend fun summarize(pastSummary: String, newMessages: List<AiAgentMsg>): String? {
        if (newMessages.isEmpty()) return pastSummary.ifBlank { null }
        currentCoroutineContext().ensureActive()
        val system = """
            你是对话记忆压缩器。把给定的对话内容压缩成一段「前情提要」。
            规则:
            - 整段重写:如果给了一版旧提要,请在旧提要基础上吸收新内容,不要重复罗列逐条消息;
            - 只保留对话后续仍然有用的信息:关键设定、人物关系、已做的决定、用户偏好、未完成的任务;
            - 无用的寒暄、过程性内容丢弃;
            - 输出为一整段中文,不超过 600 字,不要分点列表,不要加标题。
        """.trimIndent()
        val body = newMessages.joinToString("\n") { m ->
            when (m.role) {
                AiAgentMsg.ROLE_USER -> "用户:${m.content}"
                AiAgentMsg.ROLE_ASSISTANT -> "AI:${m.content}"
                AiAgentMsg.ROLE_TOOL -> "工具:${m.content}"
                else -> m.content
            }
        }.take(30_000)
        val user = buildString {
            if (pastSummary.isNotBlank()) append("已有前情提要:\n").append(pastSummary).append("\n\n")
            append("以下是新增对话内容(按时间顺序):\n").append(body)
            append("\n\n请输出整合后的新前情提要。")
        }
        val conf = AiModelResolver.resolve(AiModelResolver.Role.CHEAP)
        val sb = StringBuilder()
        return try {
            SseStreamClient.streamChat(
                system = system,
                user = user,
                baseUrl = conf.baseUrl,
                apiKey = conf.apiKey,
                model = conf.model,
                jsonMode = false,
                temperature = 0.3
            ).collect { ev ->
                when (ev) {
                    is SseStreamClient.StreamEvent.Delta -> sb.append(ev.chunk)
                    is SseStreamClient.StreamEvent.Done -> {
                        if (ev.fullContent.length > sb.length) sb.append(ev.fullContent.substring(sb.length))
                    }
                    is SseStreamClient.StreamEvent.Error -> throw ev.t
                    else -> Unit
                }
            }
            sb.toString().trim().takeIf { it.isNotBlank() }?.take(800)
        } catch (t: Throwable) {
            null
        }
    }

    /** 组装进 system 前缀的提示块;无提要则返回原 prompt 原样 */
    fun injectIntoSystem(systemPrompt: String, rollingSummary: String): String {
        if (rollingSummary.isBlank()) return systemPrompt
        return "【前情提要】(历史对话压缩,供你回忆,最近对话在下方消息中):\n$rollingSummary\n\n${systemPrompt.trim()}"
    }

    /** 校验 AppConfig 方言配置有效性(与 SseStreamClient 常量对齐) */
    val dialect: String
        get() = if (AppConfig.aiDialect == SseStreamClient.DIALECT_CLAUDE) {
            SseStreamClient.DIALECT_CLAUDE
        } else {
            SseStreamClient.DIALECT_OPENAI
        }
}