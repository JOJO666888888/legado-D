package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BreakdownChapterWithSegments
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.databinding.ActivityBookBreakdownBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.help.breakdown.BreakdownFloatingWindow
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.breakdown.BreakdownChapterAdapter.ChapterRow
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拆书档案页:书籍信息卡 + 章节列表 + 状态徽章 + 进度统计
 */
class BookBreakdownActivity : VMBaseActivity<ActivityBookBreakdownBinding, BookBreakdownViewModel>() {

    override val binding by viewBinding(ActivityBookBreakdownBinding::inflate)
    override val viewModel by viewModels<BookBreakdownViewModel>()

    private val breakdownId get() = intent.getLongExtra("id", -1L)
    private val bookChapters = MutableStateFlow<List<io.legado.app.data.entities.BookChapter>>(emptyList())
    private var breakdown: BookBreakdown? = null
    private var foldUnstarted = false
    private var floatingWindow: BreakdownFloatingWindow? = null

    private val adapter by lazy {
        BreakdownChapterAdapter(this).apply {
            callBack = object : BreakdownChapterAdapter.CallBack {
                override fun onChapterClick(chapterIndex: Int) {
                    chapterClick(chapterIndex)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvChapters.setEdgeEffectColor(primaryColor)
        binding.rvChapters.layoutManager = LinearLayoutManager(this)
        binding.rvChapters.adapter = adapter
        binding.tvFoldUnstarted.setOnClickListener {
            foldUnstarted = !foldUnstarted
            binding.tvFoldUnstarted.alpha = if (foldUnstarted) 0.4f else 1f
        }
        observeEvent<Boolean>(EventBus.MATERIALS_CHANGED) {
            refreshMaterialsBadge()
        }
        loadBreakdown()
    }

    private fun loadBreakdown() {
        lifecycleScope.launch {
            val bd = withContext(IO) { viewModel.get(breakdownId) }
                ?: run {
                    toastOnUi(R.string.breakdown_home_empty)
                    finish()
                    return@launch
                }
            breakdown = bd
            binding.titleBar.title = bd.bookName
            binding.cardInfo.setOnClickListener { showInfoDialog() }
            upInfoCard(bd)
            withContext(IO) {
                if (bd.bookUrl.isNotBlank()) {
                    bookChapters.value = viewModel.getChapterList(bd.bookUrl)
                }
            }
            attachFloatingWindow(bd)
            refreshMaterialsBadge()
            observeChapters()
        }
    }

    private fun attachFloatingWindow(bd: BookBreakdown) {
        if (floatingWindow != null) return
        if (bd.bookUrl.isBlank()) return
        floatingWindow = BreakdownFloatingWindow(
            activity = this,
            lifecycle = lifecycle,
            initialBookUrl = bd.bookUrl
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

    private fun upInfoCard(bd: BookBreakdown) {
        binding.tvCategory.text = bd.category
        binding.tvCategory.visibility = if (bd.category.isBlank()) View.GONE else View.VISIBLE
        binding.tvAchievement.text = bd.achievement
        binding.tvAchievement.visibility = if (bd.achievement.isBlank()) View.GONE else View.VISIBLE
        binding.tvTitleFormula.text = bd.titleFormula
        binding.tvTitleFormula.visibility = if (bd.titleFormula.isBlank()) View.GONE else View.VISIBLE
        binding.tvOverallNote.text = bd.overallNote
        binding.tvOverallNote.visibility = if (bd.overallNote.isBlank()) View.GONE else View.VISIBLE
        // Skill 绑定展示 + 点击切换(修复"建档案后只能默认模板"缺陷)
        lifecycleScope.launch(IO) {
            val s = io.legado.app.help.ai.AiAgentHelper.resolveBreakdownSkill(bd.skillId)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.cardSkillRow.visibility = View.VISIBLE
                binding.tvSkillName.text = buildString {
                    append(s.name)
                    append(if (s.readOnly) " (内置/只读)" else " (自定义)")
                    if (bd.skillId == 0L) append(" · 默认回退")
                }
                binding.cardSkillRow.setOnClickListener { pickSkillFor(bd) }
            }
        }
        binding.flexBenchmarks.removeAllViews()
        bd.benchmarks.forEach { benchmark ->
            val pill = io.legado.app.ui.book.material.MaterialTagViews.newPill(
                binding.flexBenchmarks.context, benchmark
            )
            pill.layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8.dpToPx(), 6.dpToPx()) }
            binding.flexBenchmarks.addView(pill)
        }
    }

    /** 档案绑定 skill 选择器(修复"建档案后只默认模板"缺陷) */
    private fun pickSkillFor(bd: BookBreakdown) {
        lifecycleScope.launch(IO) {
            val list = appDb.aiAgentSkillDao.all.filter { s ->
                s.category == io.legado.app.data.entities.AiAgentSkill.BREAKDOWN && s.enabled
            }
            if (list.isEmpty()) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    toastOnUi("暂无可用拆书 Skill,请先在 Skill 管理页导入旧模板或新建")
                }
                return@launch
            }
            val labels = list.map { s ->
                buildString {
                    append(s.name)
                    if (s.readOnly) append(" (内置)")
                    if (s.id == bd.skillId) append(" ✔")
                }
            } + listOf("使用系统默认拆书 Skill(回退)")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                selector("绑定 Skill/拆书模板", labels) { _, _, i ->
                    lifecycleScope.launch(IO) {
                        val newSkillId = when {
                            i < list.size -> list[i].id
                            else -> 0L
                        }
                        appDb.bookBreakdownDao.update(bd.copy(skillId = newSkillId, updateTime = System.currentTimeMillis()))
                        breakdown = appDb.bookBreakdownDao.get(bd.id)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            toastOnUi("已切换 Skill")
                            breakdown?.let { b -> upInfoCard(b) }
                        }
                    }
                }
            }
        }
    }

    private fun observeChapters() {
        lifecycleScope.launch {
            viewModel.flowChapters(breakdownId)
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdownChapters")
                .catch { AppLog.put("章节列表更新出错", it) }
                .flowOn(IO)
                .combine(bookChapters) { cwsList, chapters ->
                    buildRows(cwsList, chapters)
                }.collect { rows ->
                    val filtered = if (foldUnstarted) {
                        rows.filter { it.status == BreakdownHelper.STATUS_NONE }
                    } else rows
                    adapter.setItems(filtered)
                    upStats(rows)
                }
        }
    }

    private fun buildRows(
        cwsList: List<BreakdownChapterWithSegments>,
        chapters: List<io.legado.app.data.entities.BookChapter>
    ): List<ChapterRow> {
        val byIndex = cwsList.associateBy { it.chapter.chapterIndex }
        return chapters.map { chapter ->
            val record = byIndex[chapter.index]
            ChapterRow(
                chapterIndex = chapter.index,
                name = chapter.title,
                status = record?.chapter?.status ?: BreakdownHelper.STATUS_NONE,
                aiModel = record?.chapter?.aiModel.orEmpty(),
                segmentCount = record?.segmentCount ?: 0
            )
        }
    }

    private fun upStats(rows: List<ChapterRow>) {
        val done = rows.count { it.status != BreakdownHelper.STATUS_NONE }
        val draft = rows.count { it.status == BreakdownHelper.STATUS_DRAFT }
        binding.tvStats.text = getString(R.string.breakdown_stats, rows.size, done, draft)
    }

    private fun chapterClick(chapterIndex: Int) {
        val bd = breakdown ?: return
        if (bd.bookUrl.isBlank()) {
            // 无书映射档案无法拆解(提示先关联书架)
            toastOnUi(R.string.breakdown_book_not_found)
            return
        }
        startActivity<ChapterBreakdownActivity> {
            putExtra("breakdownId", bd.id)
            putExtra("chapterIndex", chapterIndex)
        }
    }

    private fun showInfoDialog() {
        showDialogFragment(BreakdownInfoDialog.newInstance(breakdownId))
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_breakdown_book, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_ai_batch -> aiBatchBreakdown()
            R.id.menu_export_book -> exportBook()
            R.id.menu_delete -> deleteBreakdown()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /* ------------------------------ AI 批量拆解 ------------------------------ */

    private fun aiBatchBreakdown() {
        if (io.legado.app.help.config.AppConfig.aiApiKey.isBlank()) {
            alert(R.string.breakdown_ai_title) {
                setMessage(R.string.breakdown_ai_key_missing)
                okButton { startActivity<AiConfigActivity>() }
                cancelButton()
            }.show()
            return
        }
        lifecycleScope.launch {
            val chapters = withContext(IO) { bookChapters.value }
            if (chapters.isEmpty()) {
                toastOnUi(R.string.breakdown_book_not_found)
                return@launch
            }
            // 过滤未拆章节,避免重拆已确认/草稿数据
            val unfinished = withContext(IO) {
                chapters.filter { ch ->
                    val rec = appDb.breakdownChapterDao.getByBreakdownAndIndex(breakdownId, ch.index)
                    rec == null || rec.status == BreakdownHelper.STATUS_NONE
                }
            }
            if (unfinished.isEmpty()) {
                toastOnUi(getString(R.string.breakdown_ai_batch_done, 0, 0))
                return@launch
            }
            // 范围选择:全部章节
            selector(
                getString(R.string.breakdown_ai_batch_scope),
                listOf<String>(getString(R.string.breakdown_ai_batch_unfinished))
            ) { _, _, index ->
                if (index == 0) {
                    runAiBatchAll(unfinished.map { it.index })
                }
            }
        }
    }

    private fun runAiBatchAll(chapterIndexes: List<Int>) {
        val target = chapterIndexes.filter { it >= 0 }
        if (target.isEmpty()) {
            toastOnUi(R.string.breakdown_export_none)
            return
        }
        val bd = breakdown ?: return
        toastOnUi(getString(R.string.breakdown_ai_batch_confirm, target.size))
        lifecycleScope.launch {
            val (pairs, numberedContents) = withContext(IO) {
                val chapters = bookChapters.value.filter { it.index in target }
                val pairsInternal = chapters.map { it.index to it.title }
                val bk = appDb.bookDao.getBook(bd.bookName, bd.bookAuthor)
                val numbered = mutableMapOf<Int, String>()
                chapters.forEach { ch ->
                    val text = if (bk != null) {
                        runCatching { io.legado.app.help.book.BookHelp.getContent(bk, ch) }.getOrNull()
                    } else null
                    if (!text.isNullOrBlank()) {
                        numbered[ch.index] = BreakdownHelper.buildNumberedContent(text)
                    }
                }
                pairsInternal to numbered
            }
            if (pairs.isEmpty()) {
                toastOnUi("所有章节内容均未缓存,请先在阅读页浏览一遍")
                return@launch
            }
            val convId = io.legado.app.help.ai.AiAgentRunner.startBreakdownTask(
                scope = lifecycleScope,
                bd = bd,
                chaptersToRun = pairs,
                numberedContents = numberedContents,
                skill = io.legado.app.help.ai.AiAgentHelper.resolveBreakdownSkill(bd.skillId)
            )
            toastOnUi("已发起批量拆解,跳转 AI Agent 辅助页查看流式进度")
            val intent = android.content.Intent(
                this@BookBreakdownActivity,
                io.legado.app.ui.book.breakdown.ai.AiAgentActivity::class.java
            ).apply { putExtra("convId", convId) }
            startActivity(intent)
            BreakdownHelper.notifyChanged()
        }
    }

    /* ------------------------------ 导出本书 ------------------------------ */

    private fun exportBook() {
        selector(
            getString(R.string.breakdown_export_menu), listOf(
                getString(R.string.breakdown_export_json),
                getString(R.string.breakdown_export_csv),
                getString(R.string.breakdown_export_md)
            )
        ) { _, _, index ->
            when (index) {
                0 -> exportBookJson()
                1 -> exportBookCsv()
                2 -> exportBookMd()
            }
        }
    }

    private fun exportBookJson() {
        lifecycleScope.launch {
            val json = withContext(IO) {
                val bd = appDb.bookBreakdownDao.get(breakdownId)
                if (bd == null) "[]"
                else {
                    val chapters = appDb.breakdownChapterDao.getByBreakdown(breakdownId)
                    val segments = appDb.breakdownSegmentDao.getByBreakdown(breakdownId)
                    val root = com.google.gson.JsonObject()
                    root.add("templates", com.google.gson.JsonArray())
                    root.add("breakdowns", io.legado.app.utils.GSON.toJsonTree(listOf(bd)))
                    root.add("chapters", io.legado.app.utils.GSON.toJsonTree(chapters))
                    root.add("segments", io.legado.app.utils.GSON.toJsonTree(segments))
                    root.toString()
                }
            }
            exportFile("${bdName()}.json", json.toByteArray())
        }
    }

    private fun exportBookCsv() {
        lifecycleScope.launch {
            val csv = withContext(IO) {
                val bd = appDb.bookBreakdownDao.get(breakdownId) ?: return@withContext null as String?
                val template = bd.templateId.takeIf { it > 0 }
                    ?.let { appDb.breakdownTemplateDao.get(it) }
                val chapters = appDb.breakdownChapterDao.getByBreakdown(breakdownId)
                val segments = appDb.breakdownSegmentDao.getByBreakdown(breakdownId)
                    .groupBy { it.chapterId }
                BreakdownHelper.toCsv(template, bd, chapters, segments)
            }
            if (csv == null) {
                toastOnUi(R.string.breakdown_book_not_found)
                return@launch
            }
            val fileBytes = csv.toByteArray(Charsets.UTF_8)
            exportFile("${bdName()}.csv", fileBytes)
        }
    }

    private fun exportBookMd() {
        lifecycleScope.launch {
            val md = withContext(IO) {
                val bd = appDb.bookBreakdownDao.get(breakdownId) ?: return@withContext null as String?
                val chapters = appDb.breakdownChapterDao.getByBreakdown(breakdownId)
                val segments = appDb.breakdownSegmentDao.getByBreakdown(breakdownId)
                    .groupBy { it.chapterId }
                BreakdownHelper.toMarkdown(bd, chapters, segments)
            }
            if (md == null) {
                toastOnUi(R.string.breakdown_book_not_found)
                return@launch
            }
            exportFile("${bdName()}.md", md.toByteArray())
        }
    }

    private fun bdName(): String {
        val n = breakdown?.bookName.orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return n.ifBlank { "breakdown" }
    }

    private fun exportFile(name: String, bytes: ByteArray) {
        exportResult.launch {
            mode = io.legado.app.ui.file.HandleFileContract.EXPORT
            fileData = io.legado.app.ui.file.HandleFileContract.FileData(
                name,
                bytes,
                "text/plain"
            )
        }
    }

    private val exportResult =
        registerForActivityResult(io.legado.app.ui.file.HandleFileContract()) {
            // 导出由文件选择页负责落盘
        }

    private fun deleteBreakdown() {
        alert(R.string.delete) {
            setMessage(getString(R.string.breakdown_purge_confirm))
            noButton()
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookBreakdownDao.softDelete(breakdownId, System.currentTimeMillis())
                    }
                    BreakdownHelper.notifyChanged()
                    toastOnUi(R.string.breakdown_deleted)
                    finish()
                }
            }
        }
    }
}