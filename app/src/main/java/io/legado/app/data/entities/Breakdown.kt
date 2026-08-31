package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 拆书模板:承载段标签词库 / AI 附加提示 / 档案字段显隐配置
 */
@TypeConverters(BreakdownTemplate.Converters::class)
@Entity(
    tableName = "breakdownTemplates",
    indices = [Index(value = ["name"], unique = true)]
)
data class BreakdownTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String = "",
    // 段功能标签词库(JSON 列)
    var segmentLabels: List<String> = emptyList(),
    // 方法论附加提示词(对标/书名/开头/循环)
    var aiPromptExtra: String = "",
    // 内置模板只读,可复制出副本
    var isBuiltin: Boolean = false,
    // 扩展配置 JSON(档案字段显隐等)
    var config: String = "{}",
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
) {
    class Converters {

        @TypeConverter
        fun labelsToString(labels: List<String>?): String = GSON.toJson(labels ?: emptyList<String>())

        @TypeConverter
        fun stringToLabels(json: String?): List<String> =
            GSON.fromJsonArray<String>(json).getOrDefault(emptyList())
    }
}

/**
 * 拆书档案(每本书一条)
 */
@TypeConverters(BookBreakdown.Converters::class)
@Entity(
    tableName = "breakdowns",
    indices = [
        Index(value = ["bookName", "bookAuthor"], unique = true),
        Index("deletedAt")
    ]
)
data class BookBreakdown(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // 冗余,删书不丢拆解(同 Material 模式)
    var bookName: String = "",
    var bookAuthor: String = "",
    // 可选回查封面/跳转
    var bookUrl: String = "",
    var templateId: Long = 0,
    // 品类,如「玄幻,东方玄幻」
    var category: String = "",
    // 成绩,如「男生月票榜NO.21」
    var achievement: String = "",
    // 书名公式,如「题材关键词+语气词+人物要素词」
    var titleFormula: String = "",
    // 对标书(JSON 列)
    var benchmarks: List<String> = emptyList(),
    // 整体节奏循环/总评
    var overallNote: String = "",
    // 0=正常;>0=回收站(软删时间)
    var deletedAt: Long = 0,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
) {
    class Converters {

        @TypeConverter
        fun benchmarksToString(benchmarks: List<String>?): String =
            GSON.toJson(benchmarks ?: emptyList<String>())

        @TypeConverter
        fun stringToBenchmarks(json: String?): List<String> =
            GSON.fromJsonArray<String>(json).getOrDefault(emptyList())
    }
}

/**
 * 章节拆解(每章一条)
 */
@Entity(
    tableName = "breakdownChapters",
    indices = [Index(value = ["breakdownId", "chapterIndex"], unique = true)]
)
data class BreakdownChapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var breakdownId: Long = 0,
    var chapterIndex: Int = 0,
    var chapterName: String = "",
    // 剧情+节奏总结
    var summary: String = "",
    // 0=未拆 1=AI草稿 2=已确认
    var status: Int = 0,
    // 生成来源(空=纯手工)
    var aiModel: String = "",
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
)

/**
 * 节奏段(每段一条)
 */
@Entity(
    tableName = "breakdownSegments",
    indices = [Index("chapterId"), Index("startPos")]
)
data class BreakdownSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var chapterId: Long = 0,
    var sortOrder: Int = 0,
    // 净化后文本行号(1 起,含端点;表格口径)
    var startLine: Int = 0,
    var endLine: Int = 0,
    // 对应文本偏移(chapterPos 口径,换行计1;跳转/回显用)
    var startPos: Int = 0,
    var endPos: Int = 0,
    // 功能标签(模板词库内或自定义)
    var label: String = "",
    // 内容简述
    var contentSummary: String = "",
    // 节奏拆解
    var rhythmNote: String = "",
    // 亮点爆点(多行文本)
    var highlights: String = "",
    // 0=正常;>0=需人工核对(AI 引文锚点校验失败,不静默丢弃)
    var needCheck: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis()
)