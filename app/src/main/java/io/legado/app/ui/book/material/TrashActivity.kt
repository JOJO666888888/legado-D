package io.legado.app.ui.book.material

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Material
import io.legado.app.databinding.ActivityMaterialListBinding
import io.legado.app.help.material.MaterialHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.material.MaterialLibraryAdapter
import io.legado.app.ui.main.material.MaterialViewItem
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
 * 素材回收站:恢复/彻底删除,不自动清理
 */
class TrashActivity : VMBaseActivity<ActivityMaterialListBinding, MaterialListViewModel>() {

    override val binding by viewBinding(ActivityMaterialListBinding::inflate)
    override val viewModel by viewModels<MaterialListViewModel>()

    private val adapter by lazy {
        MaterialLibraryAdapter(this).apply { callBack = trashCallBack }
    }
    private val trashCallBack = object : MaterialLibraryAdapter.CallBack {
        override fun onMaterialClick(material: Material) {
            showActions(material)
        }

        override fun onMaterialLongClick(material: Material) {
            showActions(material)
        }

        override fun onBookClick(bookName: String, bookAuthor: String) {}

        override fun onTagClick(tag: String) {}
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setTitle(R.string.material_trash)
        binding.rvMaterial.setEdgeEffectColor(primaryColor)
        binding.rvMaterial.layoutManager = LinearLayoutManager(this)
        binding.rvMaterial.adapter = adapter
        observeTrash()
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.material_trash, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_restore_all -> restoreAll()
            R.id.menu_clear_trash -> clearTrash()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun observeTrash() {
        lifecycleScope.launch {
            viewModel.flowTrash()
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "materials")
                .catch {
                    AppLog.put("回收站列表更新出错", it)
                }
                .flowOn(IO)
                .collect { materials ->
                    adapter.setItems(materials.map { MaterialViewItem.MaterialItem(it) })
                    binding.tvEmptyMsg.visibility =
                        if (materials.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun showActions(material: Material) {
        selector(
            material.content.take(50),
            listOf(getString(R.string.material_restore), getString(R.string.material_purge))
        ) { _, index ->
            when (index) {
                0 -> lifecycleScope.launch {
                    withContext(IO) {
                        appDb.materialDao.restore(listOf(material.id), System.currentTimeMillis())
                    }
                    MaterialHelper.notifyChanged()
                    toastOnUi(R.string.material_restore_done)
                }

                1 -> alert(R.string.material_purge) {
                    setMessage(getString(R.string.material_purge_confirm))
                    noButton()
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.materialDao.purge(listOf(material.id))
                            }
                            toastOnUi(R.string.material_purge_done)
                        }
                    }
                }
            }
        }
    }

    private fun restoreAll() {
        lifecycleScope.launch {
            val list = withContext(IO) {
                appDb.materialDao.trash
            }
            withContext(IO) {
                appDb.materialDao.restore(list.map { it.id }, System.currentTimeMillis())
            }
            MaterialHelper.notifyChanged()
            toastOnUi(R.string.material_restore_done)
        }
    }

    private fun clearTrash() {
        alert(R.string.material_clear_trash) {
            setMessage(getString(R.string.material_clear_trash_confirm))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.materialDao.clearTrash()
                    }
                    toastOnUi(R.string.material_purge_done)
                }
            }
        }
    }

}
