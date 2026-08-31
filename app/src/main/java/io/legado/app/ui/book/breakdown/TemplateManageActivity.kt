package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BreakdownTemplate
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.ActivityTemplateManageBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拆书模板管理:列表、新建、编辑、复制、删除
 * 内置模板只读,只能复制出副本编辑
 */
class TemplateManageActivity : VMBaseActivity<ActivityTemplateManageBinding, TemplateManageViewModel>() {

    override val binding by viewBinding(ActivityTemplateManageBinding::inflate)
    override val viewModel by viewModels<TemplateManageViewModel>()

    private val adapter by lazy {
        TemplateAdapter(this).apply { callBack = templateCallBack }
    }

    private val templateCallBack = object : TemplateAdapter.CallBack {
        override fun onItemClick(template: BreakdownTemplate) {
            showEditDialog(template.id)
        }

        override fun onItemLongClick(template: BreakdownTemplate) {
            showItemMenu(template)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.breakdown_template_menu)
        binding.rvTemplates.setEdgeEffectColor(primaryColor)
        binding.rvTemplates.layoutManager = LinearLayoutManager(this)
        binding.rvTemplates.adapter = adapter
        observeList()
    }

    private fun observeList() {
        lifecycleScope.launch {
            viewModel.flowAll()
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdownTemplates")
                .catch { AppLog.put("模板列表更新出错", it) }
                .flowOn(IO)
                .collect { list ->
                    adapter.setItems(list)
                    binding.tvEmptyMsg.visibility =
                        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_template_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new -> showNewDialog()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /* ------------------------------ 新建 ------------------------------ */

    private fun showNewDialog() {
        val nameEdit = io.legado.app.lib.theme.view.ThemeEditText(this).apply {
            hint = getString(R.string.breakdown_new_template)
            setSingleLine()
        }
        alert(R.string.breakdown_new_template) {
            setCustomView(nameEdit)
            okButton {
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toastOnUi(R.string.breakdown_input_book_name)
                    return@okButton
                }
                if (viewModel.getByName(name) != null) {
                    toastOnUi(R.string.breakdown_duplicate)
                    return@okButton
                }
                createNew(name)
            }
            cancelButton()
        }.show()
    }

    private fun createNew(name: String) {
        lifecycleScope.launch {
            val id = withContext(IO) {
                viewModel.insert(
                    BreakdownTemplate(
                        name = name,
                        segmentLabels = emptyList(),
                        aiPromptExtra = "",
                        isBuiltin = false
                    )
                )
            }
            BreakdownHelper.notifyChanged()
            if (id > 0) {
                showEditDialog(id)
            }
        }
    }

    /* ------------------------------ 编辑对话框 ------------------------------ */

    private fun showEditDialog(templateId: Long) {
        if (templateId <= 0) return
        showDialogFragment(TemplateEditDialog.newInstance(templateId))
    }

    /* ------------------------------ 长按菜单 ------------------------------ */

    private fun showItemMenu(template: BreakdownTemplate) {
        val items = mutableListOf<String>()
        items.add(getString(R.string.breakdown_copy_template))
        if (!template.isBuiltin) {
            items.add(getString(R.string.delete))
        }

        selector(template.name, items) { _, _, index ->
            when (index) {
                0 -> duplicateTemplate(template)
                1 -> deleteTemplate(template)
            }
        }
    }

    private fun duplicateTemplate(template: BreakdownTemplate) {
        lifecycleScope.launch {
            val newId = withContext(IO) {
                viewModel.duplicate(template)
            }
            BreakdownHelper.notifyChanged()
            if (newId > 0) {
                toastOnUi(R.string.breakdown_template_dup_done)
                showEditDialog(newId)
            }
        }
    }

    private fun deleteTemplate(template: BreakdownTemplate) {
        if (template.isBuiltin) {
            toastOnUi(R.string.breakdown_template_builtin_readonly)
            return
        }
        alert(R.string.delete) {
            setMessage(getString(R.string.breakdown_template_delete_confirm, template.name))
            okButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        viewModel.delete(template)
                    }
                    BreakdownHelper.notifyChanged()
                    toastOnUi(R.string.breakdown_template_deleted)
                }
            }
            cancelButton()
        }.show()
    }
}
