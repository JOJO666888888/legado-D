package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.parcelize.Parcelize

/**
 * 素材:阅读时划线收录的句子/段落
 */
@Parcelize
@TypeConverters(Material.Converters::class)
@Entity(
    tableName = "materials",
    indices = [Index("bookName", "bookAuthor"), Index("deletedAt")]
)
data class Material(
    // 自增主键(避免时间戳主键同毫秒收录互踩)
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // 书名(冗余存储,书籍从书架删除后素材仍保留)
    var bookName: String = "",
    var bookAuthor: String = "",
    // 冗余存储,用于回查书籍信息/跳转
    var bookUrl: String = "",
    var chapterIndex: Int = 0,
    // 选中起点在净化后章节文本中的偏移(与书签同口径,换行计1)
    var chapterPos: Int = 0,
    // 选中终点偏移(划线渲染用)
    var chapterPosEnd: Int = 0,
    var chapterName: String = "",
    // 原句(可编辑)
    var content: String = "",
    // 批注/个人思考
    var note: String = "",
    // 分类标签
    var tags: List<String> = emptyList(),
    // 星标
    var favorite: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis(),
    // 0=正常;>0=回收站(软删时间)
    var deletedAt: Long = 0
) : Parcelable {

    /**
     * 与书籍的弱关联键(书籍删除后素材仍保留)
     */
    fun bookKey(): String = "$bookName|$bookAuthor"

    /**
     * 导入去重键:书籍 + 归一化原文
     */
    fun dedupKey(): String = "$bookName|$bookAuthor|${normalizeContent(content)}"

    override fun equals(other: Any?): Boolean {
        if (other is Material) {
            return other.id == id
        }
        return false
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    companion object {

        /**
         * 去除全部空白后的原文,用于去重比对
         */
        fun normalizeContent(content: String): String =
            content.replace(Regex("\\s+"), "")
    }

    class Converters {

        @TypeConverter
        fun tagsToString(tags: List<String>?): String = GSON.toJson(tags ?: emptyList<String>())

        @TypeConverter
        fun stringToTags(json: String?): List<String> =
            GSON.fromJsonArray<String>(json).getOrDefault(emptyList())
    }
}
