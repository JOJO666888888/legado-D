package io.legado.app.ui.book.material

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Material
import io.legado.app.databinding.ActivityMaterialListBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.material.MaterialLibraryAdapter
import io.legado.app.ui.main.material.MaterialViewItem
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 书籍维度素材视图:书籍 → 章节 → 句子
 */
class BookMaterialActivity : VMBaseActivity<ActivityMaterialListBinding, MaterialListViewModel>(),
    MaterialLibraryAdapter.CallBack {

    override val binding by viewBinding(ActivityMaterialListBinding::inflate)
    override val viewModel by viewModels<MaterialListViewModel>()

    private val adapter by lazy {
        MaterialLibraryAdapter(this).apply { callBack = this@BookMaterialActivity }
    }
    private val bookName get() = intent.getStringExtra("bookName") ?: ""
    private val bookAuthor get() = intent.getStringExtra("bookAuthor") ?: ""

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = bookName.ifBlank { getString(R.string.material_library) }
        binding.rvMaterial.setEdgeEffectColor(primaryColor)
        binding.rvMaterial.layoutManager = LinearLayoutManager(this)
        binding.rvMaterial.adapter = adapter
        observeMaterials()
    }

    private fun observeMaterials() {
        lifecycleScope.launch {
            viewModel.flowByBook(bookName, bookAuthor)
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "materials")
                .catch {
                    AppLog.put("本书素材列表更新出错", it)
                }
                .flowOn(IO)
                .collect { materials ->
                    val items = arrayListOf<MaterialViewItem>()
                    var currentChapter = ""
                    for (material in materials.sortedBy { it.chapterIndex }) {
                        val chapter = material.chapterName.ifBlank {
                            getString(R.string.material_unknown_chapter)
                        }
                        if (chapter != currentChapter) {
                            items.add(MaterialViewItem.Header(chapter))
                            currentChapter = chapter
                        }
                        items.add(MaterialViewItem.MaterialItem(material))
                    }
                    adapter.setItems(items)
                    binding.tvEmptyMsg.visibility =
                        if (items.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    override fun onMaterialClick(material: Material) {
        showDialogFragment(MaterialDetailDialog.newInstance(material.id))
    }

    override fun onMaterialLongClick(material: Material) {}

    override fun onBookClick(bookName: String, bookAuthor: String) {}

    override fun onTagClick(tag: String) {
        startActivity<MaterialListActivity> {
            putExtra("tag", tag)
        }
    }

}
