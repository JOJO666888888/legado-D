package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.utils.GSON

/**
 * AI Skill / Helper 单元(最小闭环,避免依赖协程调度在 onPostMigrate 里不可用的情况)
 */
object AiAgentHelper {

    /** 确保青山式拆书默认 skill 存在(DB onOpen / 首次启动兜底)。返回它以便回填档案 skillId。 */
    fun ensureBuiltinBreakdownSkill(): AiAgentSkill? {
        val existed = appDb.aiAgentSkillDao.getByBuiltinId(AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN)
        if (existed != null) return existed
        val tpl = BreakdownHelper.builtinTemplate()
        val now = System.currentTimeMillis()
        val skill = AiAgentSkill(
            name = tpl.name,
            category = AiAgentSkill.BREAKDOWN,
            instruction = tpl.aiPromptExtra,
            vocabList = tpl.segmentLabels,
            readOnly = true,
            enabled = true,
            builtinId = AiAgentSkill.BUILTIN_BREAKDOWN_QINGSHAN,
            config = tpl.config,
            migratedFromTemplateId = -1L,
            createTime = now,
            updateTime = now
        )
        val id = appDb.aiAgentSkillDao.insert(skill).firstOrNull() ?: return null
        return appDb.aiAgentSkillDao.get(id)
    }

    /** 按档案绑定 skillId 取 skill,空则回退默认拆书 skill */
    fun resolveBreakdownSkill(skillId: Long): AiAgentSkill {
        if (skillId > 0) {
            appDb.aiAgentSkillDao.get(skillId)?.takeIf { it.enabled }?.let { return it }
        }
        return appDb.aiAgentSkillDao.getDefaultBreakdownSkill()
            ?: ensureBuiltinBreakdownSkill()
            ?: error("默认拆书 skill 不可用,请先检查数据库")
    }
}
