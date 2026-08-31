package io.legado.app.ui.book.material

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityTagManageBinding
import io.legado.app.help.material.MaterialHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理:重命名/合并/删除
 */
class TagManageActivity : VMBaseActivity<ActivityTagManageBinding, MaterialListViewModel>() {

    override val binding by viewBinding(ActivityTagManageBinding::inflate)
    override val viewModel by viewModels<MaterialListViewModel>()

    private val tags = mutableListOf<Pair<String, Int>>()
    private val adapter = object : RecyclerView.Adapter<TagHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            return TagHolder(
                LayoutInflater.from(this@TagManageActivity)
                    .inflate(R.layout.item_tag_manage, parent, false)
            )
        }

        override fun getItemCount(): Int = tags.size

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val (tag, count) = tags[position]
            holder.viewColor.setBackgroundColor(MaterialHelper.tagColor(tag))
            holder.tvName.text = tag
            holder.tvCount.text = getString(R.string.material_tag_count, count)
            holder.itemView.setOnClickListener { showActions(tag, count) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvTag.setEdgeEffectColor(primaryColor)
        binding.rvTag.layoutManager = LinearLayoutManager(this)
        binding.rvTag.adapter = adapter
        observeTags()
    }

    private fun observeTags() {
        lifecycleScope.launch {
            viewModel.flowActive()
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "materials")
                .catch {
                    AppLog.put("标签列表更新出错", it)
                }
                .flowOn(IO)
                .collect { materials ->
                    tags.clear()
                    tags.addAll(MaterialHelper.aggregateTags(materials))
                    adapter.notifyDataSetChanged()
                    binding.tvEmptyMsg.visibility =
                        if (tags.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun showActions(tag: String, count: Int) {
        selector(
            "#$tag · ${getString(R.string.material_tag_count, count)}",
            listOf(
                getString(R.string.material_tag_rename),
                getString(R.string.material_tag_merge),
                getString(R.string.material_tag_delete)
            )
        ) { _, index ->
            when (index) {
                0 -> showRenameDialog(tag)
                1 -> showMergeDialog(tag)
                2 -> alert(R.string.material_tag_delete) {
                    setMessage(getString(R.string.material_tag_delete_confirm, tag, count))
                    noButton()
                    yesButton {
                        lifecycleScope.launch {
                            MaterialHelper.removeTag(tag)
                            toastOnUi(R.string.material_tag_deleted)
                        }
                    }
                }
            }
        }
    }

    private fun showRenameDialog(tag: String) {
        val editText = EditText(this).apply {
            setText(tag)
            setSingleLine()
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        alert(R.string.material_tag_rename) {
            setCustomView(editText)
            okButton {
                val newName = editText.text?.toString()?.trim()
                if (newName.isNullOrBlank()) {
                    toastOnUi(R.string.material_content_empty)
                } else {
                    lifecycleScope.launch {
                        MaterialHelper.renameTag(tag, newName)
                        toastOnUi(R.string.material_tag_renamed)
                    }
                }
            }
            cancelButton()
        }.show()
        editText.postDelayed({
            editText.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.let {
                it.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 100)
    }

    private fun showMergeDialog(tag: String) {
        lifecycleScope.launch {
            val targets = withContext(IO) {
                MaterialHelper.aggregateTags(appDb.materialDao.getActive()).map { it.first }
                    .filter { it != tag }
            }
            if (targets.isEmpty()) {
                toastOnUi(R.string.material_tag_no_target)
                return@launch
            }
            selector(
                getString(R.string.material_tag_merge_into, tag),
                targets.map { "#$it" }
            ) { _, index ->
                lifecycleScope.launch {
                    MaterialHelper.renameTag(tag, targets[index])
                    toastOnUi(R.string.material_tag_merged)
                }
            }
        }
    }

    private inner class TagHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewColor: View = view.findViewById(R.id.view_color)
        val tvName: TextView = view.findViewById(R.id.tv_tag_name)
        val tvCount: TextView = view.findViewById(R.id.tv_tag_count)
    }

}
