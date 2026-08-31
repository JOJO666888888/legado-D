package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Menu
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
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BreakdownChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.databinding.ActivityChapterBreakdownBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章节拆解页:段落卡流 + 章末总结卡 + 草稿确认流 + 多选批量
 */
class ChapterBreakdownActivity :
    VMBaseActivity<ActivityChapterBreakdownBinding, ChapterBreakdownViewModel>(),
    BreakdownSegmentAdapter.CallBack {

    override val binding by viewBinding(ActivityChapterBreakdownBinding::inflate)
    override val viewModel by viewModels<ChapterBreakdownViewModel>()

    private val breakdownId get() = intent.getLongExtra("breakdownId", -1L)
    private val chapterIndex get() = intent.getIntExtra("chapterIndex", 0)

    private var breakdown: BookBreakdown? = null
    private var book: Book? = null
    private var chapterRec: BreakdownChapter? = null

    private val adapter by lazy {
        BreakdownSegmentAdapter(this).apply { callBack = this@ChapterBreakdownActivity }
    }
    private var summaryInput: io.legado.app.lib.theme.view.ThemeEditText? = null
    private var summaryInited = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvSegments.setEdgeEffectColor(primaryColor)
        binding.rvSegments.layoutManager = LinearLayoutManager(this)
        binding.rvSegments.adapter = adapter
        binding.tvConfirmDraft.setOnClickListener { confirmDraft() }
        binding.tvAddSegment.setOnClickListener { addSegment() }
        setupSelectActionBar()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val ctx = withContext(IO) {
                val bd = viewModel.getBreakdown(breakdownId)
                val bk = bd?.bookUrl?.takeIf { it.isNotBlank() }?.let { viewModel.getBook(it) }
                val ch = bk?.let { viewModel.getChapter(it.bookUrl, chapterIndex) }
                val record = if (bd != null && ch != null) {
                    viewModel.ensureChapter(bd.id, ch.index, ch.title)
                } else null
                Triple(bd, bk, record)
            }
            breakdown = ctx.first
            book = ctx.second
            chapterRec = ctx.third
            if (ctx.first == null || ctx.second == null || ctx.third == null) {
                toastOnUi(R.string.breakdown_book_not_found)
                finish()
                return@launch
            }
            binding.titleBar.title = ctx.third!!.chapterName
            upDraftBar(ctx.third!!)
            observeSegments(ctx.third!!.id)
        }
    }

    private fun upDraftBar(record: BreakdownChapter) {
        binding.layoutDraftBar.visibility =
            if (record.status == BreakdownHelper.STATUS_DRAFT) View.VISIBLE else View.GONE
    }

    private fun observeSegments(chapterId: Long) {
        lifecycleScope.launch {
            viewModel.flowSegments(chapterId)
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdownSegments")
                .catch { AppLog.put("段落列表更新出错", it) }
                .flowOn(IO)
                .collect { list ->
                    adapter.isAiGenerated = chapterRec?.aiModel?.isNotBlank() == true
                    adapter.setSegments(list.sortedBy { it.sortOrder })
                    initSummaryOnce()
                }
        }
    }

    /** 章末总结输入卡初始化(只执行一次,不回写覆盖用户输入) */
    private fun initSummaryOnce() {
        if (summaryInited) return
        summaryInited = true
        val record = chapterRec ?: return
        val edit = adapter.summaryView?.findViewById<io.legado.app.lib.theme.view.ThemeEditText>(R.id.edit_summary)
            ?: return
        edit.setText(record.summary)
        summaryInput = edit
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                val rec = chapterRec ?: return@afterTextChanged
                if (text != rec.summary) {
                    rec.summary = text
                    rec.updateTime = System.currentTimeMillis()
                    lifecycleScope.launch {
                        withContext(IO) {
                            viewModel.updateChapter(rec)
                            touchBreakdown()
                        }
                    }
                }
            }
        })
    }

    override fun onSegmentClick(segment: BreakdownSegment) {
        showDialogFragment(SegmentEditDialog.newInstance(breakdownId, chapterIndex, segment.id))
    }

    private fun addSegment() {
        showDialogFragment(SegmentEditDialog.newInstance(breakdownId, chapterIndex, 0L))
    }

    override fun onJumpClick(segment: BreakdownSegment) {
        jumpToSource(segment)
    }

    override fun onMove(segment: BreakdownSegment, direction: Int) {
        lifecycleScope.launch {
            withContext(IO) {
                val chapterId = segment.chapterId
                val list = viewModel.getSegments(chapterId).sortedBy { it.sortOrder }
                val index = list.indexOfFirst { it.id == segment.id }
                val target = index + direction
                if (index < 0 || target < 0 || target >= list.size) {
                    return@withContext
                }
                val a = list[index]
                val b = list[target]
                viewModel.upsertSegments(
                    a.copy(sortOrder = b.sortOrder, updateTime = System.currentTimeMillis()),
                    b.copy(sortOrder = a.sortOrder, updateTime = System.currentTimeMillis())
                )
                touchBreakdown()
            }
        }
    }

    override fun onDeleteClick(segment: BreakdownSegment) {
        alert(R.string.breakdown_delete_segment) {
            setMessage(getString(R.string.breakdown_delete_confirm))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        viewModel.deleteSegments(listOf(segment.id))
                        touchBreakdown()
                    }
                    BreakdownHelper.notifyChanged()
                }
            }
        }
    }

    override fun onSelectionChanged(selectionMode: Boolean) {
        binding.selectActionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.tvAddSegment.visibility = if (selectionMode) View.GONE else View.VISIBLE
        if (!selectionMode) {
            adapter.exitSelection()
            return
        }
        binding.selectActionBar.setMainActionText(R.string.breakdown_multi_tag)
        binding.selectActionBar.upCountView(adapter.selectedIds.size, adapter.selectableCount)
    }

    override fun onSelectionMainAction() {
        batchChangeTag()
    }

    private fun setupSelectActionBar() {
        binding.selectActionBar.setCallBack(object : SelectActionBar.CallBack {
            override fun selectAll(selectAll: Boolean) {
                adapter.selectAll(selectAll)
                binding.selectActionBar.upCountView(
                    adapter.selectedIds.size, adapter.selectableCount
                )
            }

            override fun revertSelection() {
                adapter.exitSelection()
                binding.selectActionBar.visibility = View.GONE
                binding.tvAddSegment.visibility = View.VISIBLE
            }

            override fun onClickSelectBarMainAction() {
                batchChangeTag()
            }
        })
        binding.selectActionBar.inflateMenu(R.menu.menu_breakdown_batch)
        binding.selectActionBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_delete_selected -> batchDelete()
            }
            true
        }
    }

    private fun batchDelete() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) return
        alert(R.string.breakdown_multi_delete) {
            setMessage(getString(R.string.breakdown_multi_delete_confirm, ids.size))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        viewModel.deleteSegments(ids)
                        touchBreakdown()
                    }
                    BreakdownHelper.notifyChanged()
                    adapter.exitSelection()
                    binding.selectActionBar.visibility = View.GONE
                    binding.tvAddSegment.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun batchChangeTag() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) return
        val editText = io.legado.app.lib.theme.view.ThemeEditText(this).apply {
            hint = getString(R.string.breakdown_multi_tag_input)
            setSingleLine()
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        alert(R.string.breakdown_multi_tag) {
            setCustomView(editText)
            okButton {
                val label = editText.text?.toString()?.trim().orEmpty()
                if (label.isEmpty()) return@okButton
                lifecycleScope.launch {
                    withContext(IO) {
                        viewModel.updateLabels(ids, label, System.currentTimeMillis())
                        touchBreakdown()
                    }
                    BreakdownHelper.notifyChanged()
                }
            }
            cancelButton()
        }.show()
    }

    private fun confirmDraft() {
        val record = chapterRec ?: return
        alert(R.string.breakdown_confirm) {
            setMessage(R.string.breakdown_confirm_ai_draft)
            noButton()
            yesButton {
                lifecycleScope.launch {
                    record.status = BreakdownHelper.STATUS_CONFIRMED
                    record.updateTime = System.currentTimeMillis()
                    withContext(IO) {
                        viewModel.updateChapter(record)
                        touchBreakdown()
                    }
                    BreakdownHelper.notifyChanged()
                    binding.layoutDraftBar.visibility = View.GONE
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private suspend fun touchBreakdown() {
        val bd = breakdown ?: return
        appDb.bookBreakdownDao.update(bd.copy(updateTime = System.currentTimeMillis()))
    }

    /** 跳回原文(偏移漂移兜底:本地重算后传 chapterPos) */
    private fun jumpToSource(segment: BreakdownSegment) {
        val bk = book ?: run {
            toastOnUi(R.string.breakdown_book_not_found)
            return
        }
        val ch = book?.let { viewModel.getChapter(it.bookUrl, chapterIndex) } ?: return
        lifecycleScope.launch {
            var pos = segment.startPos
            withContext(IO) {
                val lines = BreakdownHelper.getPurifiedLines(bk, ch)
                if (lines != null && segment.startLine in 1..lines.size) {
                    val expect = BreakdownHelper.rangeText(lines, segment.startLine, segment.endLine)
                    if (expect.isNotEmpty()) {
                        val full = lines.joinToString("\n")
                        BreakdownHelper.resolveRange(full, expect, segment.startPos, segment.endPos)
                            ?.let { pos = it[0] }
                    }
                }
            }
            startActivityForBook(bk) {
                putExtra("index", ch.index)
                putExtra("chapterPos", pos)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_breakdown_chapter, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_ai_chapter -> toastOnUi(R.string.breakdown_ai_config_menu)
            R.id.menu_export_chapter -> toastOnUi(R.string.breakdown_export_menu)
        }
        return super.onCompatOptionsItemSelected(item)
    }
}