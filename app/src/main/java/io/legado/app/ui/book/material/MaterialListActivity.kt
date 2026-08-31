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
 * 通用素材筛选列表(按标签/星标)
 */
class MaterialListActivity : VMBaseActivity<ActivityMaterialListBinding, MaterialListViewModel>(),
    MaterialLibraryAdapter.CallBack {

    override val binding by viewBinding(ActivityMaterialListBinding::inflate)
    override val viewModel by viewModels<MaterialListViewModel>()

    private val adapter by lazy {
        MaterialLibraryAdapter(this).apply { callBack = this@MaterialListActivity }
    }
    private val tag get() = intent.getStringExtra("tag")
    private val favorite get() = intent.getBooleanExtra("favorite", false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = when {
            !tag.isNullOrBlank() -> "#$tag"
            favorite -> getString(R.string.material_favorite)
            else -> getString(R.string.material_library)
        }
        binding.rvMaterial.setEdgeEffectColor(primaryColor)
        binding.rvMaterial.layoutManager = LinearLayoutManager(this)
        binding.rvMaterial.adapter = adapter
        observeMaterials()
    }

    private fun observeMaterials() {
        lifecycleScope.launch {
            viewModel.flowActive()
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "materials")
                .catch {
                    AppLog.put("素材列表更新出错", it)
                }
                .flowOn(IO)
                .collect { materials ->
                    val filtered = materials.filter { material ->
                        when {
                            !tag.isNullOrBlank() -> material.tags.contains(tag)
                            favorite -> material.favorite
                            else -> true
                        }
                    }
                    adapter.setItems(filtered.map { MaterialViewItem.MaterialItem(it) })
                    binding.tvEmptyMsg.visibility =
                        if (filtered.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    override fun onMaterialClick(material: Material) {
        showDialogFragment(MaterialDetailDialog.newInstance(material.id))
    }

    override fun onMaterialLongClick(material: Material) {}

    override fun onBookClick(bookName: String, bookAuthor: String) {
        startActivity<BookMaterialActivity> {
            putExtra("bookName", bookName)
            putExtra("bookAuthor", bookAuthor)
        }
    }

    override fun onTagClick(tag: String) {}

}
