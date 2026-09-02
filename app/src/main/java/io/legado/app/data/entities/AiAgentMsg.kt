package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI Agent 消息(气泡流, 一条消息 = 一个 role + content + 元数据)。
 *
 * 流式输出:
 *   1) 用户点"发送/下达任务" → 写入 USER 消息(status=DONE, deltaEmpty)
 *   2) 立即插入 ASSISTANT 消息(role=ASSISTANT, status=STREAMING, content="", deltaAccumulator 现场在内存不落地)
 *   3) SSE 增量阶段每 5~10 chunks 更新一次 content(append) + status=STREAMING
 *   4) 流式完成 → role=ASSISTANT status=DONE;若 json_mode 拆书则额外写入 status=PARSING 再切换 DONE
 *   5) 用户点击"停止生成" → 已累积 content 保留,status=CANCELLED,不写半成品到拆书表
 */
@Entity(
    tableName = "aiAgentMsgs",
    foreignKeys = [
        ForeignKey(
            entity = AiAgentConv::class,
            parentColumns = ["id"],
            childColumns = ["convId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("convId", "sortOrder"),
        Index("status"),
        Index("refBreakdownId", "refChapterIndex"),
        Index("kind")
    ]
)
data class AiAgentMsg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var convId: Long = 0L,
    /** 气泡顺序(递增),Recycler 依此展示 */
    var sortOrder: Int = 0,
    /** user / assistant / system / tool(预留) */
    var role: String = ROLE_USER,
    /** 正文(流式阶段持续拼接, 最终完整版) */
    var content: String = "",
    /** assistant 思考段(thinking/reasoning_content 增量拼合),展示时折叠 */
    var thinking: String = "",
    /** role=tool 时关联的 assistant tool_calls id(OpenAI tool_call_id) */
    var toolCallId: String = "",
    /** assistant 工具声明 JSON 数组(OpenAI tool_calls 结构原样落库,空数组表示无工具轮) */
    var toolCallsJson: String = "[]",
    /** 消息在当前会话内展示的类别:MESSAGE / CONTEXT_INJECT / BREAKDOWN_TASK / BREAKDOWN_RESULT / ERROR_BANNER */
    var kind: String = KIND_MESSAGE,
    /** 状态:STREAMING/DONE/CANCELLED/PARSING(仅消息表,不做全局 state 双写) */
    var status: Int = STATUS_DONE,
    /** 上下文注入摘要:书名×章节范围(kind=CONTEXT_INJECT 时使用) */
    var contextSummary: String = "",
    /** 拆书任务章节绑定(kind=BREAKDOWN_TASK/BREAKDOWN_RESULT 时使用) */
    var refBreakdownId: Long = 0L,
    var refChapterIndex: Int = -1,
    /** 解析后是否已成功回写拆书草稿(BREAKDOWN_RESULT 专用) */
    var written: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
) {

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
        const val ROLE_TOOL = "tool"

        const val KIND_MESSAGE = "message"
        const val KIND_CONTEXT_INJECT = "context_inject"
        const val KIND_BREAKDOWN_TASK = "breakdown_task"
        const val KIND_BREAKDOWN_RESULT = "breakdown_result"
        const val KIND_ERROR = "error"

        const val STATUS_DONE = 0
        const val STATUS_STREAMING = 1
        const val STATUS_CANCELLED = 2
        const val STATUS_PARSING = 3
    }
}
