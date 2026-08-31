package io.legado.app.ui.book.breakdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BreakdownTemplate
import io.legado.app.help.breakdown.BreakdownHelper
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class TemplateManageViewModel(application: Application) : AndroidViewModel(application) {

    fun flowAll() = appDb.breakdownTemplateDao.flowAll()

    fun get(id: Long): BreakdownTemplate? = appDb.breakdownTemplateDao.get(id)

    fun getByName(name: String): BreakdownTemplate? = appDb.breakdownTemplateDao.getByName(name)

    fun insert(template: BreakdownTemplate): Long =
        appDb.breakdownTemplateDao.insert(template).firstOrNull() ?: 0L

    fun update(template: BreakdownTemplate) = appDb.breakdownTemplateDao.update(template)

    fun delete(template: BreakdownTemplate) = appDb.breakdownTemplateDao.delete(template)

    /**
     * 复制模板为副本(内置或自定义均可),名称冲突自动加序号
     */
    suspend fun duplicate(template: BreakdownTemplate): Long = withContext(IO) {
        var newName = "${template.name}副本"
        var suffix = 1
        while (getByName(newName) != null) {
            suffix++
            newName = "${template.name}副本$suffix"
        }
        insert(
            template.copy(
                id = 0,
                name = newName,
                isBuiltin = false,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis()
            )
        )
    }
}