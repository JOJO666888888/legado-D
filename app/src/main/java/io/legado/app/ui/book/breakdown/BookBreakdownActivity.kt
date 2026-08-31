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
import io.legado.app.data.appDb
import io.legado.app.data.dao.BreakdownChapterWithSegments
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.databinding.ActivityBookBreakdownBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.breakdown.BreakdownChapterAdapter.ChapterRow
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
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
            observeChapters()
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
            R.id.menu_ai_batch -> toastOnUi(R.string.breakdown_ai_config_menu)
            R.id.menu_export_book -> toastOnUi(R.string.breakdown_export_menu)
            R.id.menu_delete -> deleteBreakdown()
        }
        return super.onCompatOptionsItemSelected(item)
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