package io.legado.app.ui.book.breakdown.ai

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.databinding.ActivityAiSkillManageBinding
import io.legado.app.help.ai.AiAgentHelper
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skill / Agent Prompt 统一管理页(替代旧 TemplateManageActivity,把拆书模板迁入 skill 体系)。
 */
class AiSkillManageActivity :
    VMBaseActivity<ActivityAiSkillManageBinding, AiSkillManageViewModel>(),
    AiSkillAdapter.CallBack {

    override val binding by viewBinding(ActivityAiSkillManageBinding::inflate)
    override val viewModel by viewModels<AiSkillManageViewModel>()

    private val adapter = AiSkillAdapter(this, this)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundColor(primaryColor)
        binding.titleBar.setTitle(R.string.ai_agent_skill_manage_title)
        val tabs = binding.tabCategory
        val categories = listOf(
            null to getString(R.string.ai_skill_cat_all),
            AiAgentSkill.BREAKDOWN to getString(R.string.ai_skill_cat_breakdown),
            AiAgentSkill.AGENT_PERSONA to getString(R.string.ai_skill_cat_persona),
            AiAgentSkill.TOOL_SKILL to getString(R.string.ai_skill_cat_tool),
            AiAgentSkill.METHODOLOGY to getString(R.string.ai_skill_cat_methodology),
            AiAgentSkill.CUSTOM to getString(R.string.ai_skill_cat_custom)
        )
        categories.forEach { (_, label) ->
            tabs.addTab(tabs.newTab().setText(label))
        }
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val cat = categories.getOrNull(tab.position)?.first
                observe(cat)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        binding.rvSkills.layoutManager = LinearLayoutManager(this)
        binding.rvSkills.adapter = adapter
        binding.fabAdd.setOnClickListener {
            showEditor(null)
        }
        observeEvent<String>(EventBus.AI_AGENT_SKILL_CHANGED) {
            // no-op, Room Flow 自动驱动刷新
        }
        observe(null)
    }

    private fun observe(category: String?) {
        val flow: Flow<List<AiAgentSkill>> = if (category == null) {
            appDb.aiAgentSkillDao.flowAll()
        } else {
            appDb.aiAgentSkillDao.flowByCategory(category)
        }
        flow
            .onEach { list ->
                adapter.items = list
                binding.tvEmptyMsg.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
            .launchIn(lifecycleScope)
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.ai_skill_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_import_skill -> toastOnUi("导入功能:请在 Activity 中接入 HandleFileContract 选 JSON 文件导入 Skill")
            R.id.menu_export_skill -> toastOnUi("导出功能:请在 Activity 中接入 HandleFileContract 导出当前 Skill 列表")
            R.id.menu_migrate_old_templates -> lifecycleScope.launch(Dispatchers.IO) {
                io.legado.app.help.ai.AiAgentTemplateMigrator.ensureMigratedInOnOpen()
                AiAgentHelper.ensureBuiltinBreakdownSkill()
                postEvent(EventBus.AI_AGENT_SKILL_CHANGED, "*")
                withContext(Dispatchers.Main) { toastOnUi(R.string.ai_skill_migrate_done) }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onClick(skill: AiAgentSkill) {
        showEditor(skill)
    }

    override fun onDuplicate(skill: AiAgentSkill) {
        lifecycleScope.launch(Dispatchers.IO) {
            val copy = skill.copy(
                id = 0,
                name = skill.name + " " + getString(R.string.ai_skill_copy_suffix),
                readOnly = false,
                builtinId = "",
                migratedFromTemplateId = -1,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis()
            )
            appDb.aiAgentSkillDao.insert(copy)
            postEvent(EventBus.AI_AGENT_SKILL_CHANGED, copy.id.toString())
            withContext(Dispatchers.Main) { toastOnUi(R.string.ai_skill_duplicated) }
        }
    }

    override fun onToggle(skill: AiAgentSkill) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiAgentSkillDao.update(skill.copy(enabled = !skill.enabled, updateTime = System.currentTimeMillis()))
            postEvent(EventBus.AI_AGENT_SKILL_CHANGED, skill.id.toString())
        }
    }

    override fun onDelete(skill: AiAgentSkill) {
        if (skill.readOnly) {
            toastOnUi(R.string.ai_skill_readonly_cant_delete)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiAgentSkillDao.delete(skill)
            postEvent(EventBus.AI_AGENT_SKILL_CHANGED, skill.id.toString())
        }
    }

    private fun showEditor(skill: AiAgentSkill?) {
        AiSkillEditor.show(this, skill) { updated ->
            lifecycleScope.launch(Dispatchers.IO) {
                if (updated.id == 0L) {
                    appDb.aiAgentSkillDao.insert(updated)
                } else {
                    appDb.aiAgentSkillDao.update(updated)
                }
                postEvent(EventBus.AI_AGENT_SKILL_CHANGED, updated.id.toString())
            }
        }
    }
}
