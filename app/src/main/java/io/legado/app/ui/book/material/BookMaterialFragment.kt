package io.legado.app.ui.book.material

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Material
import io.legado.app.databinding.FragmentBookMaterialBinding
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
 * 本书素材列表(书籍视图与目录页素材Tab共用)
 */
class BookMaterialFragment() : VMBaseFragment<MaterialListViewModel>(R.layout.fragment_book_material),
    MaterialLibraryAdapter.CallBack {

    companion object {

        fun newInstance(bookName: String, bookAuthor: String): BookMaterialFragment {
            return BookMaterialFragment().apply {
                arguments = Bundle().apply {
                    putString("bookName", bookName)
                    putString("bookAuthor", bookAuthor)
                }
            }
        }
    }

    override val viewModel by viewModels<MaterialListViewModel>()
    private val binding by viewBinding(FragmentBookMaterialBinding::bind)
    private val adapter by lazy {
        MaterialLibraryAdapter(requireContext()).apply { callBack = this@BookMaterialFragment }
    }
    private val bookName get() = arguments?.getString("bookName") ?: ""
    private val bookAuthor get() = arguments?.getString("bookAuthor") ?: ""

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvMaterial.setEdgeEffectColor(primaryColor)
        binding.rvMaterial.layoutManager = LinearLayoutManager(context)
        binding.rvMaterial.adapter = adapter
        observeMaterials()
    }

    private fun observeMaterials(searchKey: String? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.flowByBook(bookName, bookAuthor)
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED, "materials"
                )
                .catch {
                    AppLog.put("本书素材列表更新出错", it)
                }
                .flowOn(IO).collect { materials ->
                    val filtered = if (searchKey.isNullOrBlank()) {
                        materials
                    } else {
                        materials.filter { m ->
                            m.content.contains(searchKey) || m.note.contains(searchKey)
                                || m.chapterName.contains(searchKey)
                                || m.tags.any { it.contains(searchKey) }
                        }
                    }
                    val items = arrayListOf<MaterialViewItem>()
                    var currentChapter = ""
                    for (material in filtered.sortedBy { it.chapterIndex }) {
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

    /**
     * 目录页搜索联动
     */
    fun search(query: String?) {
        observeMaterials(query?.takeIf { it.isNotBlank() })
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
