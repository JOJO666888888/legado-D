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
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BreakdownChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.databinding.ActivityChapterBreakdownBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.help.breakdown.BreakdownFloatingWindow
import io.legado.app.utils.observeEvent
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
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
    private var floatingWindow: BreakdownFloatingWindow? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvSegments.setEdgeEffectColor(primaryColor)
        binding.rvSegments.layoutManager = LinearLayoutManager(this)
        binding.rvSegments.adapter = adapter
        binding.tvConfirmDraft.setOnClickListener { confirmDraft() }
        binding.tvAddSegment.setOnClickListener { addSegment() }
        setupSelectActionBar()
        observeEvent<Boolean>(EventBus.MATERIALS_CHANGED) {
            refreshMaterialsBadge()
        }
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
            attachFloatingWindow(ctx.first!!, ctx.second!!)
            refreshMaterialsBadge()
            observeSegments(ctx.third!!.id)
        }
    }

    private fun attachFloatingWindow(bd: BookBreakdown, bk: Book) {
        if (floatingWindow != null) return
        floatingWindow = BreakdownFloatingWindow(
            activity = this,
            lifecycle = lifecycle,
            initialBookUrl = bk.bookUrl,
            initialChapterIndex = chapterIndex
        ).also { it.attach() }
    }

    private fun refreshMaterialsBadge() {
        val bd = breakdown ?: return
        lifecycleScope.launch {
            val count = withContext(IO) {
                appDb.materialDao.getActive()
                    .count { it.bookName == bd.bookName && it.bookAuthor == bd.bookAuthor }
            }
            binding.titleBar.subtitle =
                getString(R.string.breakdown_floating_materials_badge, count)
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
            R.id.menu_ai_chapter -> aiBreakdownChapter()
            R.id.menu_export_chapter -> exportChapter()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /* ------------------------------ 导出本章 ------------------------------ */

    private fun exportChapter() {
        val rec = chapterRec ?: return
        selector(
            getString(R.string.breakdown_export_chapter_title), listOf(
                getString(R.string.breakdown_export_json),
                getString(R.string.breakdown_export_md)
            )
        ) { _, _, index ->
            when (index) {
                0 -> exportChapterJson()
                1 -> exportChapterMd()
            }
        }
    }

    private fun exportChapterJson() {
        lifecycleScope.launch {
            val json = withContext(IO) {
                val rec = chapterRec ?: return@withContext "[]"
                val segments = viewModel.getSegments(rec.id).sortedBy { it.sortOrder }
                val root = com.google.gson.JsonObject()
                root.add("chapters", io.legado.app.utils.GSON.toJsonTree(listOf(rec)))
                root.add("segments", io.legado.app.utils.GSON.toJsonTree(segments))
                root.toString()
            }
            exportChapterFile("${recName()}.json", json.toByteArray())
        }
    }

    private fun exportChapterMd() {
        lifecycleScope.launch {
            val md = withContext(IO) {
                val rec = chapterRec ?: return@withContext ""
                val sb = StringBuilder()
                sb.append("## ").append(rec.chapterName).append('\n')
                if (rec.summary.isNotBlank()) {
                    sb.append("**剧情+节奏总结:**").append(rec.summary).append("\n\n")
                }
                viewModel.getSegments(rec.id).sortedBy { it.sortOrder }.forEach { seg ->
                    sb.append("### ").append(seg.startLine).append('-').append(seg.endLine)
                    if (seg.label.isNotBlank()) sb.append(" · #").append(seg.label)
                    if (seg.needCheck) sb.append(" · 需人工核对")
                    sb.append("\n\n")
                    if (seg.contentSummary.isNotBlank()) sb.append("**内容简述:**").append(seg.contentSummary).append("\n\n")
                    if (seg.rhythmNote.isNotBlank()) sb.append("**节奏拆解:**").append(seg.rhythmNote).append("\n\n")
                    if (seg.highlights.isNotBlank()) {
                        sb.append("**亮点爆点:**\n")
                        seg.highlights.lines().forEach { line -> if (line.isNotBlank()) sb.append("> ").append(line).append('\n') }
                        sb.append('\n')
                    }
                }
                sb.toString()
            }
            exportChapterFile("${recName()}.md", md.toByteArray())
        }
    }

    private fun recName(): String {
        val n = chapterRec?.chapterName.orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return n.ifBlank { "chapter" }
    }

    private fun exportChapterFile(name: String, bytes: ByteArray) {
        exportResult.launch {
            mode = io.legado.app.ui.file.HandleFileContract.EXPORT
            fileData = io.legado.app.ui.file.HandleFileContract.FileData(
                name, bytes, "text/plain"
            )
        }
    }

    private val exportResult =
        registerForActivityResult(io.legado.app.ui.file.HandleFileContract()) {
            // 导出由文件选择页负责落盘
        }

    /* ------------------------------ AI 拆解本章 ------------------------------ */

    private fun aiBreakdownChapter() {
        val record = chapterRec ?: return
        if (io.legado.app.help.config.AppConfig.aiApiKey.isBlank()) {
            alert(R.string.breakdown_ai_title) {
                setMessage(R.string.breakdown_ai_key_missing)
                okButton { startActivity<AiConfigActivity>() }
                cancelButton()
            }.show()
            return
        }
        // 已确认章:允许重新拆解但需二次确认(不静默覆盖)
        if (record.status == BreakdownHelper.STATUS_CONFIRMED) {
            alert(R.string.breakdown_ai_title) {
                setMessage(R.string.breakdown_ai_rerun_confirm)
                okButton { runAiChapter() }
                cancelButton()
            }.show()
        } else {
            runAiChapter()
        }
    }

    private fun runAiChapter() {
        val bd = breakdown ?: run {
            binding.tvAddSegment.isEnabled = true
            return
        }
        val bookLocal = book
        lifecycleScope.launch {
            val (chaptersToRun, numberedContents) = withContext(IO) {
                val ch = bookChaptersForBreakdown(bookLocal, bd, listOf(chapterIndex))
                buildNumberedMap(bd, ch)
            }
            if (chaptersToRun.isEmpty()) {
                toastOnUi("没有可用章节")
                binding.tvAddSegment.isEnabled = true
                return@launch
            }
            val convId = io.legado.app.help.ai.AiAgentRunner.startBreakdownTask(
                scope = lifecycleScope,
                bd = bd,
                chaptersToRun = chaptersToRun,
                numberedContents = numberedContents,
                skill = io.legado.app.help.ai.AiAgentHelper.resolveBreakdownSkill(bd.skillId)
            )
            toastOnUi("已发起 AI 拆解,已切到 AI Agent 页实时查看进度")
            // 切到主界面 AI Agent Tab 展示流式过程(Tab 隐藏时由 MainActivity 降级为独立页)
            val intent = android.content.Intent(
                this@ChapterBreakdownActivity,
                io.legado.app.ui.main.MainActivity::class.java
            ).apply {
                putExtra("aiAgentConvId", convId)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
            binding.tvAddSegment.isEnabled = true
            BreakdownHelper.notifyChanged()
            loadData()
        }
    }

    /* ------------------------------ 辅助:构造拆书 Runner 需要的 (章节→预编号文本) ------------------------------ */

    private suspend fun bookChaptersForBreakdown(
        bk: Book?,
        bd: BookBreakdown,
        indices: List<Int>
    ): List<BookChapter> {
        val chapters = if (bk != null) {
            appDb.bookChapterDao.getChapterList(bk.bookUrl)
        } else {
            // 缺 bookUrl 时退回根据 bd.bookName/author 回查的 book
            val b = appDb.bookDao.getBook(bd.bookName, bd.bookAuthor)
                ?: return emptyList()
            appDb.bookChapterDao.getChapterList(b.bookUrl)
        }
        return chapters.filter { it.index in indices }
    }

    private suspend fun buildNumberedMap(
        bd: BookBreakdown,
        chapters: List<BookChapter>
    ): Pair<List<Pair<Int, String>>, Map<Int, String>> {
        val pairs = mutableListOf<Pair<Int, String>>()
        val map = mutableMapOf<Int, String>()
        val bk = appDb.bookDao.getBook(bd.bookName, bd.bookAuthor)
        chapters.forEach { ch ->
            pairs += ch.index to ch.title
            val text = if (bk != null) {
                runCatching { io.legado.app.help.book.BookHelp.getContent(bk, ch) }.getOrNull()
            } else null
            if (!text.isNullOrBlank()) {
                map[ch.index] = BreakdownHelper.buildNumberedContent(text)
            }
        }
        return pairs to map
    }
}