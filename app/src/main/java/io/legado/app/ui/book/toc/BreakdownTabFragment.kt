package io.legado.app.ui.book.toc

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.databinding.FragmentBreakdownTabBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.ui.book.breakdown.BookBreakdownActivity
import io.legado.app.ui.book.breakdown.BreakdownChapterAdapter
import io.legado.app.ui.book.breakdown.BreakdownHomeActivity
import io.legado.app.ui.book.breakdown.ChapterBreakdownActivity
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目录页第 4 Tab:本书拆解状态一览(章列表 + 状态徽章),直达章节拆解 / 创建档案
 */
class BreakdownTabFragment() : BaseFragment(R.layout.fragment_breakdown_tab) {

    private val binding by viewBinding(FragmentBreakdownTabBinding::bind)

    private var breakdownId = -1L
    private var bookUrl = ""
    private var bookName = ""

    private val adapter by lazy {
        BreakdownChapterAdapter(requireContext()).apply {
            callBack = object : BreakdownChapterAdapter.CallBack {
                override fun onChapterClick(chapterIndex: Int) {
                    if (breakdownId > 0) {
                        startActivity<ChapterBreakdownActivity> {
                            putExtra("breakdownId", breakdownId)
                            putExtra("chapterIndex", chapterIndex)
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            bookUrl = it.getString("bookUrl").orEmpty()
            bookName = it.getString("bookName").orEmpty()
        }
        binding.rvBreakdown.setEdgeEffectColor(requireContext().primaryColor)
        binding.rvBreakdown.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBreakdown.adapter = adapter
        binding.tvCreate.setOnClickListener {
            startActivity<BreakdownHomeActivity>()
        }
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            appDb.bookBreakdownDao.flowByBookUrl(bookUrl)
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdowns")
                .catch { AppLog.put("目录拆书Tab更新出错", it) }
                .flowOn(IO)
                .collect { list ->
                    val bd = list.firstOrNull()
                    if (bd == null) {
                        breakdownId = -1L
                        binding.llEmpty.visibility = View.VISIBLE
                        binding.rvBreakdown.visibility = View.GONE
                        adapter.setItems(emptyList())
                        return@collect
                    }
                    breakdownId = bd.id
                    binding.llEmpty.visibility = View.GONE
                    binding.rvBreakdown.visibility = View.VISIBLE
                    val chapters = withContext(IO) {
                        appDb.bookChapterDao.getChapterList(bookUrl)
                    }
                    val records = withContext(IO) {
                        appDb.breakdownChapterDao.getByBreakdown(bd.id)
                            .associateBy { it.chapterIndex }
                    }
                    val rows = chapters.map { ch ->
                        val r = records[ch.index]
                        BreakdownChapterAdapter.ChapterRow(
                            chapterIndex = ch.index,
                            name = ch.title,
                            status = r?.status ?: BreakdownHelper.STATUS_NONE,
                            aiModel = r?.aiModel.orEmpty(),
                            segmentCount = r?.let { re ->
                                withContext(IO) { appDb.breakdownSegmentDao.getByChapter(re.id).size }
                            } ?: 0
                        )
                    }
                    adapter.setItems(rows)
                }
        }
    }
}