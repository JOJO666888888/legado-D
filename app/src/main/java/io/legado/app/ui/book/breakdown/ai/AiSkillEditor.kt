package io.legado.app.ui.book.breakdown.ai

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.databinding.DialogAiSkillEditorBinding
import io.legado.app.utils.GSON
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * Skill/Agent 编辑器弹窗。包含:名称/分类/SystemPrompt/Instruction/词汇表(逗号分隔)/工具描述/只读开关/扩展配置
 */
object AiSkillEditor {

    fun show(activity: FragmentActivity, skill: AiAgentSkill?, onSave: (AiAgentSkill) -> Unit) {
        val dlg = EditorDialog(activity, skill ?: AiAgentSkill(), onSave)
        dlg.show()
    }

    private class EditorDialog(
        context: Context,
        private val initial: AiAgentSkill,
        private val onSave: (AiAgentSkill) -> Unit
    ) : AlertDialog(context) {

        private val binding by lazy {
            DialogAiSkillEditorBinding.inflate(layoutInflater).also { it }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setView(binding.root)
            setTitle(if (initial.id == 0L) "新建 Skill/Agent 提示词" else "编辑 Skill/Agent 提示词")
            binding.tieName.setText(initial.name)
            val catIndex = when (initial.category) {
                AiAgentSkill.BREAKDOWN -> 0
                AiAgentSkill.AGENT_PERSONA -> 1
                AiAgentSkill.TOOL_SKILL -> 2
                AiAgentSkill.METHODOLOGY -> 3
                else -> 4
            }
            binding.spCategory.setSelection(catIndex)
            binding.tieSystem.setText(initial.systemPrompt)
            binding.tieInstruction.setText(initial.instruction)
            binding.tieVocab.setText(initial.vocabList.joinToString("、"))
            binding.tieTool.setText(initial.toolDescriptions)
            binding.cbReadOnly.isEnabled = !initial.readOnly // 内置只读不可变
            binding.cbReadOnly.isChecked = initial.readOnly
            binding.tieConfig.setText(initial.config)
            setButton(BUTTON_POSITIVE, "保存") { _, _ ->
                val name = binding.tieName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    binding.tilName.error = "名称必填"
                    return@setButton
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
                onSave(updated)
            }
            setButton(BUTTON_NEGATIVE, "取消") { d, _ -> d.dismiss() }
        }
    }
}
