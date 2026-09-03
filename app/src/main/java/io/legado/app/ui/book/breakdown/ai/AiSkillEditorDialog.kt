package io.legado.app.ui.book.breakdown.ai

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.databinding.DialogAiSkillEditorBinding
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skill/Agent 提示词编辑器(基于 BaseDialogFragment,替代旧的 AlertDialog 子类方案)。
 * 名称/分类/SystemPrompt/Instruction/词汇表/工具描述/只读开关/扩展配置。
 */
class AiSkillEditorDialog : BaseDialogFragment(R.layout.dialog_ai_skill_editor, true) {

    companion object {
        fun newInstance(skill: AiAgentSkill?): AiSkillEditorDialog =
            AiSkillEditorDialog().apply {
                arguments = Bundle().apply {
                    putString("skillJson", GSON.toJson(skill ?: AiAgentSkill()))
                }
            }
    }

    private val binding by viewBinding(DialogAiSkillEditorBinding::bind)
    private var initial: AiAgentSkill = AiAgentSkill()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val attr = attributes
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 修复背景透明:BaseDialogFragment 在 adaptationSoftKeyboard=true 时会把窗口背景置为透明,
            // 而本布局根视图无背景,导致弹窗与下层"Skill/Agent 管理"画面重叠难读。
            // 此处用主题不透明背景色覆盖,浅色/深色主题下均与下层内容完全隔离。
            setBackgroundDrawable(ColorDrawable(ThemeStore.backgroundColor()))
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val json = arguments?.getString("skillJson").orEmpty()
        initial = runCatching { GSON.fromJson(json, AiAgentSkill::class.java) }
            .getOrDefault(AiAgentSkill())
        with(binding) {
            tieName.setText(initial.name)
            spCategory.setSelection(categoryIndex(initial.category))
            tieSystem.setText(initial.systemPrompt)
            tieInstruction.setText(initial.instruction)
            tieVocab.setText(initial.vocabList.joinToString("、"))
            tieTool.setText(initial.toolDescriptions)
            tieConfig.setText(initial.config)
            cbReadOnly.isEnabled = !initial.readOnly
            cbReadOnly.isChecked = initial.readOnly
            tvSave.setOnClickListener { save() }
            tvCancel.setOnClickListener { dismiss() }
        }
    }

    private fun categoryIndex(cat: String): Int = when (cat) {
        AiAgentSkill.BREAKDOWN -> 0
        AiAgentSkill.AGENT_PERSONA -> 1
        AiAgentSkill.TOOL_SKILL -> 2
        AiAgentSkill.METHODOLOGY -> 3
        else -> 4
    }

    private fun save() {
        val name = binding.tieName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            toastOnUi(R.string.ai_skill_name_required)
            return
        }
        val category = when (binding.spCategory.selectedItemPosition) {
            0 -> AiAgentSkill.BREAKDOWN
            1 -> AiAgentSkill.AGENT_PERSONA
            2 -> AiAgentSkill.TOOL_SKILL
            3 -> AiAgentSkill.METHODOLOGY
            else -> AiAgentSkill.CUSTOM
        }
        val vocab = binding.tieVocab.text?.toString().orEmpty()
            .split(',', '，', '、', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val updated = initial.copy(
            name = name,
            category = category,
            systemPrompt = binding.tieSystem.text?.toString().orEmpty(),
            instruction = binding.tieInstruction.text?.toString().orEmpty(),
            vocabList = vocab,
            toolDescriptions = binding.tieTool.text?.toString().orEmpty(),
            readOnly = binding.cbReadOnly.isChecked || initial.readOnly,
            config = binding.tieConfig.text?.toString()?.takeIf { it.isNotBlank() } ?: "{}",
            updateTime = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            withContext(IO) {
                if (updated.id == 0L) {
                    appDb.aiAgentSkillDao.insert(updated)
                } else {
                    appDb.aiAgentSkillDao.update(updated)
                }
            }
            postEvent(EventBus.AI_AGENT_SKILL_CHANGED, updated.id.toString())
            toastOnUi(R.string.action_save)
            dismiss()
        }
    }
}