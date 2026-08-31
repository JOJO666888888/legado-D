package io.legado.app.ui.main.material

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Material
import io.legado.app.help.material.MaterialHelper
import io.legado.app.help.material.MaterialHelper.ImportStrategy
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 素材库视图项
 */
sealed class MaterialViewItem {
    /** 标签面板(标签视图) */
    data class TagPanel(val tags: List<Pair<String, Int>>) : MaterialViewItem()

    /** 书籍行(书籍视图) */
    data class BookRow(
        val bookName: String,
        val bookAuthor: String,
        val count: Int,
        val lastTime: Long
    ) : MaterialViewItem()

    /** 分组头(时间线) */
    data class Header(val title: String) : MaterialViewItem()

    /** 素材卡片 */
    data class MaterialItem(val material: Material) : MaterialViewItem()
}

class MaterialLibraryViewModel(application: Application) : BaseViewModel(application) {

    val searchKey = MutableLiveData<String?>(null)

    /** 待导出素材(导出回调取用) */
    var exportList: List<Material> = emptyList()

    fun flowActiveMaterials() = appDb.materialDao.flowActive()

    fun flowSearch(key: String) = appDb.materialDao.flowSearch(key)

    fun flowTrash() = appDb.materialDao.flowTrash()

    fun exportJson(uri: Uri, materials: List<Material>) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "material-${dateFormat.format(Date())}.json"
            val dirDoc = FileDoc.fromUri(uri, true)
            dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow().use {
                GSON.writeToOutputStream(it, materials)
            }
        }.onError {
            AppLog.put("素材导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi(R.string.material_export_done)
        }
    }

    fun exportMd(uri: Uri, materials: List<Material>) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "material-${dateFormat.format(Date())}.md"
            val dirDoc = FileDoc.fromUri(uri, true)
            dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow().use {
                it.write(MaterialHelper.toMarkdown(materials).toByteArray())
            }
        }.onError {
            AppLog.put("素材导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi(R.string.material_export_done)
        }
    }

    /**
     * 解析导入文件,回调 (总数, 与现有库重复数)
     */
    fun parseImportFile(uri: Uri, onResult: (List<Material>, Int) -> Unit) {
        execute {
            val text = FileDoc.fromUri(uri, false).readText()
            val parsed = when {
                text.trimStart().startsWith("[") -> MaterialHelper.fromJson(text)
                else -> MaterialHelper.fromMarkdown(text)
            }
            val existingKeys = appDb.materialDao.getActive().map { it.dedupKey() }.toSet()
            val duplicate = parsed.count { existingKeys.contains(it.dedupKey()) }
            Pair(parsed, duplicate)
        }.onSuccess {
            onResult(it.first, it.second)
        }.onError {
            AppLog.put("素材导入解析失败\n${it.localizedMessage}", it, true)
        }
    }

    fun importMaterials(list: List<Material>, strategy: ImportStrategy) {
        execute {
            val result = MaterialHelper.import(list, strategy)
            context.toastOnUi(
                context.getString(
                    R.string.material_import_result,
                    result.total, result.imported, result.skipped, result.merged
                )
            )
        }.onError {
            AppLog.put("素材导入失败\n${it.localizedMessage}", it, true)
        }
    }

    fun importFromBookmarks() {
        execute {
            val result = MaterialHelper.importFromBookmarks()
            context.toastOnUi(
                context.getString(
                    R.string.material_import_result,
                    result.total, result.imported, result.skipped, result.merged
                )
            )
        }.onError {
            AppLog.put("书签导入失败\n${it.localizedMessage}", it, true)
        }
    }

    fun moveToTrash(ids: List<Long>) {
        execute {
            appDb.materialDao.moveToTrash(ids, System.currentTimeMillis())
            MaterialHelper.notifyChanged()
        }.onSuccess {
            context.toastOnUi(R.string.material_moved_to_trash)
        }.onError {
            AppLog.put("移入回收站失败\n${it.localizedMessage}", it, true)
        }
    }

    fun addTags(ids: List<Long>, tags: List<String>) {
        execute {
            MaterialHelper.addTags(ids, tags)
        }.onError {
            AppLog.put("批量打标签失败\n${it.localizedMessage}", it, true)
        }
    }

}

