package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

/**
 * AI Agent 会话(一条会话承载一次连续对话或一个长任务,如「拆书《青山》第 1 章」)。
 *
 * 拆书联动约定:
 *   - 从拆书房"AI 拆解本章/批量"新建会话时:kind=TASK_BREAKDOWN,refBreakdownId/refChapterIndex 非空
 *   - 拆书会话会复用:同一档案同一 skill(同一批批量)复用一个 convId,直到用户手动删除或超过活跃窗口
 *   - 避免脏会话:convKey = "breakdown:{bdId}:{skillId}"(批量则附带 batchId),存在则复用,不重复创建
 */
@TypeConverters(AiAgentConv.Converters::class)
@Entity(
    tableName = "aiAgentConvs",
    indices = [
        Index(value = ["convKey"], unique = true),
        Index("updateTime"),
        Index("kind"),
        Index("refBreakdownId", "refChapterIndex")
    ]
)
data class AiAgentConv(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 业务去重 key;普通对话取 UUID,拆书取 "breakdown:{bdId}:{skillId}[:{batchId}]" */
    var convKey: String = "",
    var title: String = "",
    /** 分类:CHAT / TASK_BREAKDOWN / TASK_OTHER */
    var kind: String = CHAT,
    /** 主 skill id(拆书场景=档案绑定 skill;空=默认拆书 skill 回退) */
    var skillId: Long = 0L,
    /** 会话启用的额外 skill ids(多选,附加到 system prompt) */
    var extraSkillIds: List<Long> = emptyList(),
    /** 绑定的拆书档案 id(TASK_BREAKDOWN 时使用) */
    var refBreakdownId: Long = 0L,
    /** 拆书时的章节索引(单章时填;批量时填 -1,由每条消息 refChapterIndex 区分) */
    var refChapterIndex: Int = -1,
    /** 进行中:0=空闲 1=流式生成中 2=结果解析中 */
    var state: Int = STATE_IDLE,
    /** 最近一次错误提示(空=正常) */
    var lastError: String = "",
    /** 前情提要(rolling summary):AI 对历史的长篇压缩,注入 system 前缀,防止"聊到一半忘开头" */
    var rollingSummary: String = "",
    /** 前情提要水位:已纳入 summary 的最后一条消息 id(0=尚未生成) */
    var summarizedThroughMessageId: Long = 0L,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
) {

    class Converters {

        @TypeConverter
        fun listToJson(list: List<Long>?): String =
            GSON.toJson(list ?: emptyList<Long>())

        @TypeConverter
        fun jsonToList(json: String?): List<Long> =
            GSON.fromJsonArray<Long>(json).getOrDefault(emptyList())
    }

    companion object {
        const val CHAT = "chat"
        const val TASK_BREAKDOWN = "breakdown"
        const val TASK_OTHER = "task_other"

        const val STATE_IDLE = 0
        const val STATE_STREAMING = 1
        const val STATE_PARSING = 2

        fun buildBreakdownKey(bdId: Long, skillId: Long, batchId: String? = null): String =
            "breakdown:$bdId:$skillId" + if (batchId.isNullOrBlank()) "" else ":$batchId"
    }
}
