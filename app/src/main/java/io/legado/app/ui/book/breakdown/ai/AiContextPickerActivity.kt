package io.legado.app.ui.book.breakdown.ai

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityAiContextPickerBinding
import io.legado.app.help.ai.EmbeddingPipeline
import io.legado.app.help.ai.ModelCapabilities
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择器:自由指定 书籍 + 章节范围,将对应正文作为上下文注入当前对话。
 */
class AiContextPickerActivity :
    VMBaseActivity<ActivityAiContextPickerBinding, AiContextPickerViewModel>() {

    override val binding by viewBinding(ActivityAiContextPickerBinding::inflate)
    override val viewModel by viewModels<AiContextPickerViewModel>()

    private var currentBookUrl: String = ""
    private var bookName: String = ""
    private var author: String = ""
    private var startIdx = 0
    private var endIdx = 0
    private var chapters: List<io.legado.app.data.entities.BookChapter> = emptyList()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundColor(primaryColor)
        binding.titleBar.setTitle(R.string.ai_agent_context_pick_title)
        val budget = ModelCapabilities.injectionCharsBudget(AppConfig.aiModel)
        binding.tvBudget.text = getString(R.string.ai_agent_context_budget_tip, budget)
        binding.btnPickBook.setOnClickListener { pickBook() }
        binding.btnConfirm.setOnClickListener { confirmAndReturn() }
        binding.seekStart.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                startIdx = progress
                if (startIdx > endIdx && endIdx != 0) startIdx = endIdx
                updateRangeSummary()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
        })
        binding.seekEnd.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                endIdx = progress
                if (endIdx < startIdx) endIdx = startIdx
                updateRangeSummary()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
        })
    }

    private fun pickBook() {
        lifecycleScope.launch(Dispatchers.IO) {
            val books = appDb.bookDao.all
            if (books.isEmpty()) {
                withContext(Dispatchers.Main) { toastOnUi("书架为空,请先导入书籍") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val title = getString(R.string.ai_agent_context_pick_title)
                val items: kotlin.collections.ArrayList<CharSequence> =
                    books.mapTo(kotlin.collections.ArrayList()) { "${it.name} · ${it.author}" }
                selector(title, items) { _, which ->
                    val bk = books[which]
                    currentBookUrl = bk.bookUrl
                    bookName = bk.name
                    author = bk.author
                    binding.tvSelectedBook.text = "《${bk.name}》作者 ${bk.author}"
                    loadChapters()
                }
            }
        }
    }

    private fun loadChapters() {
        if (currentBookUrl.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val list = appDb.bookChapterDao.getChapterList(currentBookUrl)
            chapters = list
            if (list.isEmpty()) {
                withContext(Dispatchers.Main) {
                    toastOnUi("目录为空,请先从阅读页打开一下该书籍")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val max = (list.size - 1).coerceAtLeast(0)
                binding.seekStart.max = max
                binding.seekStart.progress = 0
                binding.seekEnd.max = max
                binding.seekEnd.progress = max.coerceAtMost(19)
                startIdx = 0
                endIdx = binding.seekEnd.progress
                updateRangeSummary()
            }
        }
    }

    private fun updateRangeSummary() {
        if (chapters.isEmpty()) {
            binding.tvRangeSummary.text = "尚未选择书籍"
            return
        }
        val s = startIdx.coerceAtMost(endIdx)
        val e = endIdx.coerceAtLeast(s)
        startIdx = s; endIdx = e
        val names = chapters.subList(s, e + 1).joinToString("、") { it.title }.take(120)
        binding.tvRangeSummary.text = "选择章节(${s + 1}~${e + 1}): $names"
    }

    private fun confirmAndReturn() {
        if (chapters.isEmpty()) {
            toastOnUi("请先选择书籍")
            return
        }
        val s = startIdx.coerceAtMost(endIdx)
        val e = endIdx.coerceAtLeast(s)
        val intent = Intent().apply {
            putExtra("bookName", bookName)
            putExtra("author", author)
            putExtra("startIdx", s)
            putExtra("endIdx", e)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    /* ------------------------------ 语义索引(M9) ------------------------------ */

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_context_picker, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_build_semantic_index -> {
                buildSemanticIndex()
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 建立当前所选书籍的整书语义索引(异步,进度 toast) */
    private fun buildSemanticIndex() {
        if (currentBookUrl.isBlank()) {
            toastOnUi("请先在下方选择书籍")
            return
        }
        val bookUrl = currentBookUrl
        lifecycleScope.launch(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(bookUrl) ?: return@launch
            val err = EmbeddingPipeline.embedBook(book) { _, _ -> }
            withContext(Dispatchers.Main) {
                toastOnUi(err ?: getString(R.string.ai_agent_index_done))
            }
        }
    }
}
