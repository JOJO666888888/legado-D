package io.legado.app.ui.main.material

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Material
import io.legado.app.databinding.FragmentMaterialLibraryBinding
import io.legado.app.help.material.MaterialHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.material.BookMaterialActivity
import io.legado.app.ui.book.material.MaterialDetailDialog
import io.legado.app.ui.book.material.MaterialListActivity
import io.legado.app.ui.book.material.TagManageActivity
import io.legado.app.ui.book.material.TrashActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 素材库主界面:时间线 / 书籍 / 标签
 */
class MaterialLibraryFragment() : VMBaseFragment<MaterialLibraryViewModel>(R.layout.fragment_material_library),
    MainFragmentInterface,
    MaterialLibraryAdapter.CallBack {

    companion object {
        const val TAB_TIMELINE = 0
        const val TAB_BOOK = 1
        const val TAB_TAG = 2
    }

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<MaterialLibraryViewModel>()
    private val binding by viewBinding(FragmentMaterialLibraryBinding::bind)
    private val adapter by lazy { MaterialLibraryAdapter(requireContext()).apply { callBack = this@MaterialLibraryFragment } }
    private var searchView: SearchView? = null
    private var tabLayout: TabLayout? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initTabLayout()
        initRecyclerView()
        initSearchView()
        observeMaterials()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_material, menu)
        menu.findItem(R.id.menu_search)?.let {
            searchView = it.actionView as? SearchView
        }
    }

    private fun initTabLayout() {
        tabLayout = binding.titleBar.findViewById(R.id.tab_layout)
        tabLayout?.setSelectedTabIndicatorColor(primaryColor)
        tabLayout?.isTabIndicatorFullWidth = false
        tabLayout?.addTab(tabLayout!!.newTab().setText(R.string.material_timeline))
        tabLayout?.addTab(tabLayout!!.newTab().setText(R.string.material_books))
        tabLayout?.addTab(tabLayout!!.newTab().setText(R.string.material_tags))
        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                adapter.setItems(emptyList())
                observeMaterials()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initRecyclerView() {
        binding.rvMaterial.setEdgeEffectColor(primaryColor)
        binding.rvMaterial.layoutManager = LinearLayoutManager(context)
        binding.rvMaterial.adapter = adapter
        initSelectActionBar()
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setCallBack(object : SelectActionBar.CallBack {
            override fun selectAll(selectAll: Boolean) {
                adapter.selectAll(selectAll)
                upSelectBar()
            }

            override fun revertSelection() {
                adapter.selectableCount
                adapter.selectedIds.clear()
                adapter.items.filterIsInstance<MaterialViewItem.MaterialItem>()
                    .forEach { item ->
                        if (item.material.id !in adapter.selectedIds) {
                            adapter.selectedIds.add(item.material.id)
                        }
                    }
                adapter.notifyDataSetChanged()
                upSelectBar()
            }

            override fun onClickSelectBarMainAction() {
                showBatchTagDialog()
            }
        })
        binding.selectActionBar.setMainActionText(R.string.material_batch_tag)
        binding.selectActionBar.inflateMenu(R.menu.material_select)?.let { menu ->
            menu.findItem(R.id.menu_batch_move_to_trash).setOnMenuItemClickListener {
                viewModel.moveToTrash(adapter.selectedIds.toList())
                exitSelectionMode()
                true
            }
            menu.findItem(R.id.menu_batch_export).setOnMenuItemClickListener {
                exportMaterials(adapter.selectedMaterials())
                true
            }
            menu.findItem(R.id.menu_batch_exit).setOnMenuItemClickListener {
                exitSelectionMode()
                true
            }
        }
    }

    private fun upSelectBar() {
        val visible = adapter.selectionMode
        binding.selectActionBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.selectActionBar.upCountView(adapter.selectedIds.size, adapter.selectableCount)
        }
    }

    private fun exitSelectionMode() {
        adapter.clearSelection()
        upSelectBar()
    }

    /**
     * 批量打标签(逗号/空格分隔)
     */
    private fun showBatchTagDialog() {
        val context = context ?: return
        val editText = android.widget.EditText(context).apply {
            hint = getString(R.string.material_input_tag)
            setSingleLine()
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        alert(R.string.material_batch_tag) {
            setCustomView(editText)
            okButton {
                val tags = editText.text?.toString()
                    ?.split(',', '，', ' ', '、')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                if (tags.isNotEmpty()) {
                    viewModel.addTags(adapter.selectedIds.toList(), tags)
                    exitSelectionMode()
                }
            }
            cancelButton()
        }.show()
    }

    private fun exportMaterials(materials: List<Material>) {
        if (materials.isEmpty()) return
        viewModel.exportList = materials
        showExportFormatDialog()
    }

    private fun initSearchView() {
        searchView?.apply {
            applyTint(primaryTextColor)
            isSubmitButtonEnabled = true
            queryHint = getString(R.string.search_material_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.searchKey.postValue(newText?.takeIf { it.isNotBlank() })
                    observeMaterials()
                    return false
                }
            })
        }
    }

    private fun observeMaterials() {
        val tab = tabLayout?.selectedTabPosition ?: TAB_TIMELINE
        val searchKey = viewModel.searchKey.value
        viewLifecycleOwner.lifecycleScope.launch {
            val flow = when {
                !searchKey.isNullOrBlank() -> viewModel.flowSearch(searchKey)
                else -> viewModel.flowActiveMaterials()
            }
            flow.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                "materials"
            ).catch {
                AppLog.put("素材列表更新出错", it)
            }.flowOn(IO).collect { materials ->
                val items = when (tab) {
                    TAB_BOOK -> upBookItems(materials)
                    TAB_TAG -> listOf(
                        MaterialViewItem.TagPanel(MaterialHelper.aggregateTags(materials))
                    )

                    else -> upTimelineItems(materials)
                }
                adapter.setItems(items)
                binding.tvEmptyMsg.visibility =
                    if (items.isEmpty() && searchKey.isNullOrBlank()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun upTimelineItems(materials: List<Material>): List<MaterialViewItem> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val todayStart = calendar.timeInMillis
        val yesterdayStart = todayStart - 24 * 3600 * 1000L
        val weekStart = todayStart - 7 * 24 * 3600 * 1000L
        val items = arrayListOf<MaterialViewItem>()
        var currentGroup = ""
        for (material in materials) {
            val group = when {
                material.createTime >= todayStart -> getString(R.string.material_today)
                material.createTime >= yesterdayStart -> getString(R.string.material_yesterday)
                material.createTime >= weekStart -> getString(R.string.material_this_week)
                else -> getString(R.string.material_earlier)
            }
            if (group != currentGroup) {
                items.add(MaterialViewItem.Header(group))
                currentGroup = group
            }
            items.add(MaterialViewItem.MaterialItem(material))
        }
        return items
    }

    private fun upBookItems(materials: List<Material>): List<MaterialViewItem> {
        return materials.groupBy { it.bookName to it.bookAuthor }
            .map { (key, list) ->
                MaterialViewItem.BookRow(
                    bookName = key.first,
                    bookAuthor = key.second,
                    count = list.size,
                    lastTime = list.maxOf { it.createTime }
                )
            }
            .sortedByDescending { it.lastTime }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_material_trash -> startActivity<TrashActivity>()
            R.id.menu_material_tag_manage -> startActivity<TagManageActivity>()
            R.id.menu_import_material -> importFile.launch {
                title = getString(R.string.material_import)
                allowExtensions = arrayOf("json", "md")
            }

            R.id.menu_import_bookmark -> alert(R.string.material_import_bookmark) {
                setMessage(R.string.material_import_bookmark_confirm)
                noButton()
                yesButton {
                    viewModel.importFromBookmarks()
                }
            }

            R.id.menu_export_material -> showExportDialog()
        }
    }

    private fun showExportDialog() {
        lifecycleScope.launch(IO) {
            viewModel.exportList = appDb.materialDao.getActive()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                showExportFormatDialog()
            }
        }
    }

    private fun showExportFormatDialog() {
        alert(R.string.material_export) {
            items(
                listOf(
                    getString(R.string.material_export_json),
                    getString(R.string.material_export_md)
                )
            ) { _, index ->
                exportDir.launch {
                    requestCode = index + 1
                }
            }
        }
    }

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val materials = viewModel.exportList
            when (result.requestCode) {
                1 -> viewModel.exportJson(uri, materials)
                2 -> viewModel.exportMd(uri, materials)
            }
        }
    }

    private val importFile = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showImportStrategyDialog(uri) }
    }

    private fun showImportStrategyDialog(uri: Uri) {
        viewModel.parseImportFile(uri) { parsed, duplicate ->
            if (parsed.isEmpty()) {
                context?.toastOnUi(R.string.material_import_empty)
                return@parseImportFile
            }
            alert(R.string.material_import) {
                setMessage(
                    getString(R.string.material_import_preview, parsed.size, duplicate)
                )
                singleChoiceItems(
                    arrayOf(
                        getString(R.string.material_import_skip),
                        getString(R.string.material_import_all),
                        getString(R.string.material_import_merge)
                    )
                ) { _, which ->
                    val strategy = when (which) {
                        1 -> MaterialHelper.ImportStrategy.IMPORT_ALL
                        2 -> MaterialHelper.ImportStrategy.MERGE
                        else -> MaterialHelper.ImportStrategy.SKIP_DUPLICATES
                    }
                    viewModel.importMaterials(parsed, strategy)
                }
                cancelButton()
            }
        }
    }

    override fun onMaterialClick(material: Material) {
        if (adapter.selectionMode) {
            adapter.toggleSelect(material)
            upSelectBar()
        } else {
            showDialogFragment(MaterialDetailDialog.newInstance(material.id))
        }
    }

    override fun onMaterialLongClick(material: Material) {
        adapter.selectionMode = true
        adapter.selectedIds.add(material.id)
        adapter.notifyDataSetChanged()
        upSelectBar()
    }

    override fun onBookClick(bookName: String, bookAuthor: String) {
        startActivity<BookMaterialActivity> {
            putExtra("bookName", bookName)
            putExtra("bookAuthor", bookAuthor)
        }
    }

    override fun onTagClick(tag: String) {
        startActivity<MaterialListActivity> {
            putExtra("tag", tag)
        }
    }

}
