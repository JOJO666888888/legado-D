package io.legado.app.help.ai

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 旧拆书模板 → AI Skill 一次性迁移导入器(迁移后不再维护两套并行体系)。
 *
 * 触发点:
 *   1. AppDatabase Migration_91_92.onPostMigrate(覆盖安装升级路径);
 *   2. AppDatabase.dbCallback.onOpen(首次创建 + 已升级数据库二次兜底)。
 *      幂等: 已迁移(migratedFromTemplateId>0)的模板不会重复导入。
 */
object AiAgentTemplateMigrator {

    private const val PREF_KEY_MIGRATED = "ai_agent_templates_migrated_v1"

    @Volatile
    private var doneFlag: Boolean = false

    fun markDoneLocally() {
        doneFlag = true
        kotlin.runCatching {
            appCtx.defaultSharedPreferences
                .edit().putBoolean(PREF_KEY_MIGRATED, true).apply()
        }
    }

    fun isMigrated(): Boolean {
        if (doneFlag) return true
        return kotlin.runCatching {
            appCtx.defaultSharedPreferences.getBoolean(PREF_KEY_MIGRATED, false)
        }.getOrDefault(false)
    }

    /** Migration_91_92 调用入口: 直接用 SupportSQLiteDatabase(Room AutoMigration 阶段 DAO 尚未就绪)。 */
    fun migrateLegacyTemplates(db: SupportSQLiteDatabase) {
        try {
            ensureBuiltinBreakdownQingshan(db)
            val cursor = db.query("select * from breakdownTemplates order by isBuiltin desc, id asc")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val templateId = c.getLong(c.getColumnIndexOrThrow("id"))
                    val migratedCount = db.query(
                        "select count(*) from aiAgentSkills where migratedFromTemplateId = ?",
                        arrayOf(templateId.toString())
                    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (migratedCount > 0) continue

                    val name = c.getString(c.getColumnIndexOrThrow("name")) ?: ""
                    val labelsJson = c.getString(c.getColumnIndexOrThrow("segmentLabels")) ?: "[]"
                    val extra = c.getString(c.getColumnIndexOrThrow("aiPromptExtra")) ?: ""
                    val builtin = c.getInt(c.getColumnIndexOrThrow("isBuiltin")) == 1
                    val config = c.getString(c.getColumnIndexOrThrow("config")) ?: "{}"
                    val cTime = c.getLong(c.getColumnIndexOrThrow("createTime"))
                    val uTime = c.getLong(c.getColumnIndexOrThrow("updateTime"))

                    val builtinId = if (builtin && matchesQingshanName(name)) {
                        AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN
                    } else ""

                    val cv = ContentValues().apply {
                        put("name", name)
                        put("category", AiAgentSkill.BREAKDOWN)
                        put("systemPrompt", "")
                        put("instruction", extra)
                        put("vocabList", labelsJson)
                        put("toolDescriptions", "")
                        put("readOnly", if (builtin) 1 else 0)
                        put("enabled", 1)
                        put("builtinId", builtinId)
                        put("config", config)
                        put("migratedFromTemplateId", templateId)
                        put("createTime", cTime)
                        put("updateTime", uTime)
                    }
                    db.insert("aiAgentSkills", SQLiteDatabase.CONFLICT_REPLACE, cv)
                }
            }
            runCatching {
                db.execSQL(
                    """
                    update breakdowns set skillId = coalesce(
                      (select id from aiAgentSkills where migratedFromTemplateId = breakdowns.templateId limit 1),
                      (select id from aiAgentSkills where builtinId = 'breakdown_qingshan' limit 1),
                      (select id from aiAgentSkills where category = 'breakdown' and enabled = 1 order by readOnly desc limit 1),
                      0
                    ) where skillId is null or skillId = 0
                    """.trimIndent()
                )
            }
            markDoneLocally()
        } catch (t: Throwable) {
            android.util.Log.e("AiAgentMigrate", "模板迁移失败", t)
        }
    }

    /** DB onOpen 兜底入口:已完成则跳过,否则用 appDb 再迁一次。 */
    fun ensureMigratedInOnOpen() {
        if (isMigrated()) return
        CoroutineScope(Dispatchers.IO).launch {
            kotlin.runCatching {
                val tpls = appDb.breakdownTemplateDao.all
                if (tpls.isEmpty()) {
                    AiAgentHelper.ensureBuiltinBreakdownSkill()
                    markDoneLocally()
                    return@launch
                }
                val skillDao = appDb.aiAgentSkillDao
                tpls.forEach { tpl ->
                    val existing = skillDao.getByMigratedTemplateId(tpl.id)
                    if (existing != null) return@forEach
                    val builtinId = if (tpl.isBuiltin && matchesQingshanName(tpl.name)) {
                        AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN
                    } else ""
                    val s = AiAgentSkill(
                        name = tpl.name,
                        category = AiAgentSkill.BREAKDOWN,
                        instruction = tpl.aiPromptExtra,
                        vocabList = tpl.segmentLabels,
                        readOnly = tpl.isBuiltin,
                        enabled = true,
                        builtinId = builtinId,
                        config = tpl.config,
                        migratedFromTemplateId = tpl.id,
                        createTime = tpl.createTime,
                        updateTime = tpl.updateTime
                    )
                    skillDao.insert(s)
                }
                val bdDao = appDb.bookBreakdownDao
                val allBds = bdDao.all
                allBds.forEach { bd ->
                    val target = skillDao.getByMigratedTemplateId(bd.templateId)
                        ?: AiAgentHelper.ensureBuiltinBreakdownSkill()
                    if (bd.skillId == 0L && target != null) {
                        bdDao.update(bd.copy(skillId = target.id, updateTime = System.currentTimeMillis()))
                    }
                }
                markDoneLocally()
            }.onFailure {
                android.util.Log.e("AiAgentMigrate", "onOpen 迁移失败", it)
            }
        }
    }

    private fun ensureBuiltinBreakdownQingshan(db: SupportSQLiteDatabase) {
        val exist = db.query(
            "select count(*) from aiAgentSkills where builtinId = ?",
            arrayOf(AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (exist > 0) return
        val tpl = BreakdownHelper.builtinTemplate()
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("name", tpl.name)
            put("category", AiAgentSkill.BREAKDOWN)
            put("systemPrompt", "")
            put("instruction", tpl.aiPromptExtra)
            put("vocabList", GSON.toJson(tpl.segmentLabels))
            put("toolDescriptions", "")
            put("readOnly", 1)
            put("enabled", 1)
            put("builtinId", AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN)
            put("config", tpl.config)
            put("migratedFromTemplateId", -1L)
            put("createTime", now)
            put("updateTime", now)
        }
        db.insert("aiAgentSkills", SQLiteDatabase.CONFLICT_IGNORE, cv)
    }

    private fun matchesQingshanName(name: String): Boolean =
        name.contains("青山", true) ||
            name.contains("qingshan", true) ||
            name == "默认拆解模板" ||
            name.contains("默认模板", true)
}
