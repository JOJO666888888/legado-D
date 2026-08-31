package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.readText
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.ActivityBreakdownHomeBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拆书房:档案列表(封面+进度+AI草稿徽章),搜索,新建档案
 */
class BreakdownHomeActivity : VMBaseActivity<ActivityBreakdownHomeBinding, BreakdownHomeViewModel>() {

    override val binding by viewBinding(ActivityBreakdownHomeBinding::inflate)
    override val viewModel by viewModels<BreakdownHomeViewModel>()

    private val adapter by lazy {
        BreakdownHomeAdapter(this).apply { callBack = homeCallBack }
    }
    private val homeCallBack = object : BreakdownHomeAdapter.CallBack {
        override fun onBreakdownClick(breakdown: io.legado.app.data.entities.BookBreakdown) {
            startActivity<BookBreakdownActivity> {
                putExtra("id", breakdown.id)
            }
        }

        override fun onBreakdownLongClick(breakdown: io.legado.app.data.entities.BookBreakdown) {
            // 预留:长按即回收站/删除,与素材库卡片交互一致
            showDeleteMenu(breakdown)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.breakdown_home)
        binding.rvBreakdown.setEdgeEffectColor(primaryColor)
        binding.rvBreakdown.layoutManager = LinearLayoutManager(this)
        binding.rvBreakdown.adapter = adapter
        binding.editSearch.setSingleLine()
        observeList()
    }

    private fun observeList() {
        val searchKey = MutableStateFlow("")
        lifecycleScope.launch {
            combine(viewModel.flowAll(), searchKey) { list, key ->
                if (key.isEmpty()) list
                else list.filter { it.breakdown.bookName.contains(key, true) }
            }.flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdowns")
                .catch { AppLog.put("拆书房列表更新出错", it) }
                .flowOn(IO)
                .collect { filtered ->
                    adapter.setItems(filtered)
                    binding.tvEmptyMsg.visibility =
                        if (filtered.isEmpty()) View.VISIBLE else View.GONE
                }
        }
        binding.editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchKey.value = s?.toString()?.trim().orEmpty()
            }
        })
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_breakdown_home, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new -> showNewDialog()
            R.id.menu_template -> startActivity<TemplateManageActivity>()
            R.id.menu_import -> importFile.launch {
                mode = io.legado.app.ui.file.HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("json")
            }

            R.id.menu_export -> exportAllJson()
            R.id.menu_ai_config -> startActivity<AiConfigActivity>()
            R.id.menu_trash -> startActivity<BreakdownTrashActivity>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /* ------------------------------ 导出/导入 ------------------------------ */

    private val exportResult =
        registerForActivityResult(io.legado.app.ui.file.HandleFileContract()) {
            // 导出由文件选择页负责落盘
        }

    private fun exportAllJson() {
        toastOnUi(R.string.breakdown_export_done)
        lifecycleScope.launch {
            val json = withContext(IO) { buildFullJson() }
            val name = "breakdown备份_${System.currentTimeMillis()}.json"
            exportResult.launch {
                mode = io.legado.app.ui.file.HandleFileContract.EXPORT
                fileData = io.legado.app.ui.file.HandleFileContract.FileData(
                    name,
                    json.toByteArray(),
                    "application/json"
                )
            }
        }
    }

    private fun buildFullJson(): String {
        val root = com.google.gson.JsonObject()
        root.add("templates", io.legado.app.utils.GSON.toJsonTree(appDb.breakdownTemplateDao.all))
        root.add("breakdowns", io.legado.app.utils.GSON.toJsonTree(appDb.bookBreakdownDao.all))
        root.add("chapters", io.legado.app.utils.GSON.toJsonTree(appDb.breakdownChapterDao.all))
        root.add("segments", io.legado.app.utils.GSON.toJsonTree(appDb.breakdownSegmentDao.all))
        return root.toString()
    }

    private val importFile =
        registerForActivityResult(io.legado.app.ui.file.HandleFileContract()) {
            val uri = it.uri
            if (uri == null) {
                toastOnUi(getString(R.string.breakdown_import_failed, "null uri"))
                return@registerForActivityResult
            }
            importFromUri(uri)
        }

    private fun importFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(IO) { uri.readText(this@BreakdownHomeActivity) }
                val bundle = BreakdownHelper.parseImportBundle(text)
                if (bundle == null) {
                    toastOnUi(R.string.breakdown_import_empty)
                    return@launch
                }
                withContext(IO) {
                    if (bundle.templates.isNotEmpty()) {
                        appDb.breakdownTemplateDao.insert(*bundle.templates.toTypedArray())
                    }
                    if (bundle.breakdowns.isNotEmpty()) {
                        appDb.bookBreakdownDao.insert(*bundle.breakdowns.toTypedArray())
                    }
                    if (bundle.chapters.isNotEmpty()) {
                        appDb.breakdownChapterDao.upsert(*bundle.chapters.toTypedArray())
                    }
                    if (bundle.segments.isNotEmpty()) {
                        appDb.breakdownSegmentDao.upsert(*bundle.segments.toTypedArray())
                    }
                }
                BreakdownHelper.notifyChanged()
                toastOnUi(getString(
                    R.string.breakdown_import_res,
                    bundle.breakdowns.size, bundle.chapters.size, bundle.segments.size
                ))
            } catch (e: Exception) {
                toastOnUi(getString(R.string.breakdown_import_failed, e.message ?: "error"))
            }
        }
    }

    /* ------------------------------ 新建档案 ------------------------------ */

    private fun showNewDialog() {
        val context = this
        val nameEdit = ThemeEditText(context).apply {
            hint = getString(R.string.breakdown_input_book_name)
            setSingleLine()
        }
        val authorEdit = ThemeEditText(context).apply {
            hint = getString(R.string.breakdown_input_book_author)
            setSingleLine()
        }
        val tvPickBook = io.legado.app.ui.widget.text.AccentTextView(context, null).apply {
            text = getString(R.string.breakdown_select_book)
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
            setOnClickListener {
                pickBookFromShelf { book ->
                    nameEdit.setText(book.name)
                    authorEdit.setText(book.author)
                }
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 8.dpToPx()
            setPadding(pad, pad, pad, pad)
            addView(nameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(authorEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tvPickBook)
        }
        alert(R.string.breakdown_new_dialog_title) {
            setCustomView(container)
            okButton {
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val author = authorEdit.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toastOnUi(R.string.breakdown_input_book_name)
                    return@okButton
                }
                val dup = viewModel.getActiveByBook(name, author)
                if (dup != null) {
                    toastOnUi(R.string.breakdown_duplicate)
                    return@okButton
                }
                pickTemplateAndCreate(name, author, "")
            }
            cancelButton()
        }.show()
    }

    private fun pickBookFromShelf(onPicked: (io.legado.app.data.entities.Book) -> Unit) {
        lifecycleScope.launch {
            val books = withContext(IO) { appDb.bookDao.all }
            if (books.isEmpty()) {
                toastOnUi(R.string.breakdown_input_book_name)
                return@launch
            }
            selector(
                getString(R.string.breakdown_select_book),
                books.map { "${it.name}  ${it.author}" }
            ) { _, _, index ->
                onPicked(books[index])
            }
        }
    }

    private fun pickTemplateAndCreate(name: String, author: String, bookUrl: String) {
        val templates = viewModel.getTemplates()
        if (templates.isEmpty()) {
            toastOnUi(R.string.breakdown_select_template)
            return
        }
        selector(
            getString(R.string.breakdown_select_template),
            templates.map { it.name }
        ) { _, _, index ->
            createBreakdown(name, author, bookUrl, templates[index].id)
        }
    }

    private fun createBreakdown(name: String, author: String, bookUrl: String, templateId: Long) {
        lifecycleScope.launch {
            val id = withContext(IO) {
                viewModel.insert(BookBreakdown(
                    bookName = name,
                    bookAuthor = author,
                    bookUrl = bookUrl,
                    templateId = templateId
                ))
            }
            BreakdownHelper.notifyChanged()
            if (id > 0) {
                startActivity<BookBreakdownActivity> { putExtra("id", id) }
            }
        }
    }

    /* ------------------------------ 长按菜单 ------------------------------ */

    private fun showDeleteMenu(breakdown: io.legado.app.data.entities.BookBreakdown) {
        selector(
            breakdown.bookName,
            listOf(getString(R.string.breakdown_open), getString(R.string.delete))
        ) { _, _, index ->
            when (index) {
                0 -> startActivity<BookBreakdownActivity> { putExtra("id", breakdown.id) }
                1 -> lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookBreakdownDao.softDelete(breakdown.id, System.currentTimeMillis())
                    }
                    BreakdownHelper.notifyChanged()
                    toastOnUi(R.string.breakdown_deleted)
                }
            }
        }
    }
}