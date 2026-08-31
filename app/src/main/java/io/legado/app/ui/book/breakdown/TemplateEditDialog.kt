package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BreakdownTemplate
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.ui.book.material.MaterialTagViews
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogTemplateEditBinding
import io.legado.app.lib.theme.view.ThemeEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模板编辑底部弹层:名称、段标签词库 chips 管理、AI 提示词附加、档案字段显隐开关
 * 内置模板只读(名称与词库不可改),可另存副本
 */
class TemplateEditDialog() : BaseDialogFragment(R.layout.dialog_template_edit, true) {

    companion object {
        fun newInstance(templateId: Long): TemplateEditDialog {
            return TemplateEditDialog().apply {
                arguments = Bundle().apply {
                    putLong("templateId", templateId)
                }
            }
        }
    }

    private val binding by viewBinding(DialogTemplateEditBinding::bind)
    private val templateId get() = arguments?.getLong("templateId") ?: 0L

    private var template: BreakdownTemplate? = null
    private val labelWords = arrayListOf<String>()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val attr = attributes
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            val loaded = withContext(IO) {
                if (templateId > 0) appDb.breakdownTemplateDao.get(templateId)
                else null
            }
            // 新建无模板对象时,由调用方通过 newInstance(templateId=0) 建好再打开
            template = loaded
            if (loaded == null) {
                dismiss()
                return@launch
            }
            initUi(loaded)
        }
    }

    private fun initUi(t: BreakdownTemplate) {
        binding.editName.setText(t.name)
        // 内置模板只读
        binding.editName.isEnabled = !t.isBuiltin
        binding.editAiPrompt.setText(t.aiPromptExtra)
        // 字段显隐
        val fields = parseFields(t.config)
        binding.swCategory.isChecked = fields["category"] ?: true
        binding.swAchievement.isChecked = fields["achievement"] ?: true
        binding.swTitleFormula.isChecked = fields["titleFormula"] ?: true
        binding.swBenchmarks.isChecked = fields["benchmarks"] ?: true
        binding.swOverall.isChecked = fields["overallNote"] ?: true
        binding.swCategory.isEnabled = !t.isBuiltin
        binding.swAchievement.isEnabled = !t.isBuiltin
        binding.swTitleFormula.isEnabled = !t.isBuiltin
        binding.swBenchmarks.isEnabled = !t.isBuiltin
        binding.swOverall.isEnabled = !t.isBuiltin
        if (labelWords.isNotEmpty()) labelWords.clear()
        labelWords.addAll(t.segmentLabels)
        upLabels()
        binding.tvAddLabel.setOnClickListener {
            val word = binding.editNewLabel.text?.toString()?.trim().orEmpty()
            if (word.isEmpty()) {
                toastOnUi(R.string.breakdown_multi_tag_input)
                return@setOnClickListener
            }
            if (template?.isBuiltin == true) {
                toastOnUi(R.string.breakdown_template_builtin_readonly)
                return@setOnClickListener
            }
            if (!labelWords.contains(word)) {
                labelWords.add(word)
                binding.editNewLabel.text = null
                upLabels()
            }
        }
        binding.tvSave.setOnClickListener { save(t) }
        // 复制按钮:内置->存副本;自定义->存副本
        binding.tvDuplicate.setOnClickListener { duplicate(t) }
        binding.tvDelete.setOnClickListener {
            if (t.isBuiltin) {
                toastOnUi(R.string.breakdown_template_builtin_readonly)
                return@setOnClickListener
            }
            doDelete(t)
        }
    }

    private fun upLabels() {
        val context = context ?: return
        binding.flexLabels.removeAllViews()
        labelWords.forEach { word ->
            val pill = MaterialTagViews.newPill(
                context, word,
                closable = template?.isBuiltin != true,
                onClick = null,
                onClose = {
                    labelWords.remove(word)
                    upLabels()
                }
            )
            pill.layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8.dpToPx(), 6.dpToPx()) }
            binding.flexLabels.addView(pill)
        }
    }

    private fun parseFields(config: String): Map<String, Boolean> {
        val result = hashMapOf<String, Boolean>()
        runCatching {
            val obj = GSON.fromJson(config, com.google.gson.JsonObject::class.java)
                ?: com.google.gson.JsonObject()
            val fields = obj.getAsJsonObject("fields") ?: return@runCatching
            fields.keySet().forEach { key ->
                result[key] = fields.get(key).takeIf { it != null && !it.isJsonNull }?.asBoolean ?: true
            }
        }
        return result
    }

    private fun buildConfig(): String {
        val obj = com.google.gson.JsonObject()
        val fields = com.google.gson.JsonObject()
        fields.addProperty("category", binding.swCategory.isChecked)
        fields.addProperty("achievement", binding.swAchievement.isChecked)
        fields.addProperty("titleFormula", binding.swTitleFormula.isChecked)
        fields.addProperty("benchmarks", binding.swBenchmarks.isChecked)
        fields.addProperty("overallNote", binding.swOverall.isChecked)
        obj.add("fields", fields)
        return obj.toString()
    }

    private fun save(t: BreakdownTemplate) {
        if (t.isBuiltin) {
            toastOnUi(R.string.breakdown_template_builtin_readonly)
            return
        }
        val name = binding.editName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.breakdown_input_book_name)
            return
        }
        lifecycleScope.launch {
            withContext(IO) {
                val updated = t.copy(
                    name = name,
                    segmentLabels = labelWords.toList(),
                    aiPromptExtra = binding.editAiPrompt.text?.toString()?.trim().orEmpty(),
                    config = buildConfig(),
                    updateTime = System.currentTimeMillis()
                )
                appDb.breakdownTemplateDao.update(updated)
            }
            BreakdownHelper.notifyChanged()
            toastOnUi(R.string.breakdown_template_saved)
            dismiss()
        }
    }

    private fun duplicate(t: BreakdownTemplate) {
        lifecycleScope.launch {
            val newId = withContext(IO) {
                val vm = TemplateManageViewModel(requireActivity().application)
                vm.duplicate(t)
            }
            if (newId > 0) {
                BreakdownHelper.notifyChanged()
                dismiss()
            }
        }
    }

    private fun doDelete(t: BreakdownTemplate) {
        lifecycleScope.launch {
            withContext(IO) {
                appDb.breakdownTemplateDao.delete(t)
            }
            BreakdownHelper.notifyChanged()
            toastOnUi(R.string.breakdown_template_deleted)
            dismiss()
        }
    }
}