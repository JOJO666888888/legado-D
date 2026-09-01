package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * AI Agent Skill / Agent 提示词管理单元(统一托管旧拆书模板、Agent 人设、通用技能提示词)。
 *
 * 迁移映射(来自旧 BreakdownTemplate 一次性迁入,之后不再并行维护两套):
 *   BreakdownTemplate.name          → AiAgentSkill.name
 *   BreakdownTemplate.segmentLabels → AiAgentSkill.vocabList (词汇表/标签词库)
 *   BreakdownTemplate.aiPromptExtra → AiAgentSkill.instruction (方法论/附加提示词)
 *   BreakdownTemplate.isBuiltin     → AiAgentSkill.readOnly (只读属性,内置 skill 不可直接编辑,可复制出副本)
 *   BreakdownTemplate.config        → AiAgentSkill.config
 *
 * 分类说明:
 *   category: "breakdown"(拆书)、"agent_persona"(agent 人设)、"tool_skill"(技能/工具描述)、"methodology"(方法论)、"custom"(自定义)
 *   builtinId: 内置 skill 的稳定 id(如 "breakdown_qingshan"),用于跨设备识别;自定义可空
 */
@TypeConverters(AiAgentSkill.Converters::class)
@Entity(
    tableName = "aiAgentSkills",
    indices = [
        Index(value = ["name", "category"], unique = true),
        Index("category"),
        Index("builtinId", unique = true)
    ]
)
data class AiAgentSkill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String = "",
    var category: String = CUSTOM,
    /** system prompt / 技能主描述 */
    var systemPrompt: String = "",
    /** 方法论 / AI 附加提示词 / 工具描述(替换旧 aiPromptExtra) */
    var instruction: String = "",
    /** 词汇表:拆书段标签、人设专属词汇、领域术语(替换旧 segmentLabels) */
    var vocabList: List<String> = emptyList(),
    /** 技能/工具描述(JSON 数组 or 自然语言段落) */
    var toolDescriptions: String = "",
    /** 只读(内置 skill 为 true),副本为 false 可编辑 */
    var readOnly: Boolean = false,
    /** 是否启用 */
    var enabled: Boolean = true,
    /** 内置 skill 的稳定标识(如 breakdown_qingshan) */
    var builtinId: String = "",
    /** 扩展配置 JSON,兼容旧模板 config 字段 */
    var config: String = "{}",
    /** 旧模板 id,迁移追踪用, -1 表示非模板来源 */
    var migratedFromTemplateId: Long = -1L,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
) {

    class Converters {

        @TypeConverter
        fun listToJson(list: List<String>?): String =
            GSON.toJson(list ?: emptyList<String>())

        @TypeConverter
        fun jsonToList(json: String?): List<String> =
            GSON.fromJsonArray<String>(json).getOrDefault(emptyList())
    }

    companion object {
        const val BREAKDOWN = "breakdown"
        const val AGENT_PERSONA = "agent_persona"
        const val TOOL_SKILL = "tool_skill"
        const val METHODOLOGY = "methodology"
        const val CUSTOM = "custom"

        /** 青山式拆解(内置默认拆书 skill) — builtinId 稳定,后续默认回退用 */
        const val BUILTIN_BREAKDOWN_QINGSHAN = "breakdown_qingshan"
    }
}
