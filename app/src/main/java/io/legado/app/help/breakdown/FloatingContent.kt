package io.legado.app.help.breakdown

import android.content.Context
import android.text.Selection
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleCoroutineScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Material
import io.legado.app.databinding.FloatingBreakdownPanelBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.material.MaterialHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拆书原文悬浮窗:业务控制器
 *
 * - 自由选书/选章并加载对应章节原文
 * - 可选中的 TextView 展示全文(上下文浏览)
 * - 从选中文本一键「收录素材」,复用现有 Material 实体 + DAO
 * - 写入后通过 [MaterialHelper.notifyChanged] 广播 EventBus.MATERIALS_CHANGED,
 *   宿主 Activity 若观察该事件即可实时感知素材变动
 */
class FloatingContent(
    private val activity: androidx.fragment.app.FragmentActivity,
    private val binding: FloatingBreakdownPanelBinding,
    private val scope: LifecycleCoroutineScope,
    private val initialBookUrl: String? = null,
    private val initialChapterIndex: Int? = null,
    private val onClose: () -> Unit,
) {

    private val ctx: Context get() = activity

    private var currentBook: Book? = null
    private var currentChapter: BookChapter? = null
    private var currentContent: String = ""

    fun init() {
        binding.btnFloatingClose.setOnClickListener { onClose() }
        binding.btnFloatingPickBook.setOnClickListener { showBookPicker() }
        binding.btnFloatingPickChapter.setOnClickListener { showChapterPicker() }
        binding.btnFloatingCollect.setOnClickListener { collectSelectedText() }
        // 优先使用初始上下文
        scope.launch {
            if (initialBookUrl.isNullOrBlank()) {
                showEmpty(getString(R.string.breakdown_floating_pick_book))
            } else {
                val book = withContext(IO) { appDb.bookDao.getBook(initialBookUrl) }
                if (book != null) {
                    currentBook = book
                    val idx = initialChapterIndex ?: 0
                    val chapter = withContext(IO) {
                        appDb.bookChapterDao.getChapter(book.bookUrl, idx)
                    } ?: withContext(IO) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).firstOrNull()
                    }
                    if (chapter != null) {
                        bindChapter(chapter)
                    } else {
                        bindBookMeta()
                        showEmpty(getString(R.string.breakdown_floating_pick_chapter))
                    }
                } else {
                    showEmpty(getString(R.string.breakdown_floating_pick_book))
                }
            }
        }
    }

    /* =============================== 选书/选章 =============================== */

    private fun showBookPicker() = scope.launch {
        val books = withContext(IO) { appDb.bookDao.all }
        if (books.isEmpty()) {
            ctx.toastOnUi(R.string.breakdown_input_book_name)
            return@launch
        }
        activity.selector(
            ctx.getString(R.string.breakdown_floating_pick_book),
            books.map { "${it.name}  ${it.author}" }
        ) { _, _, index ->
            val picked = books[index]
            currentBook = picked
            scope.launch {
                val chapters = withContext(IO) {
                    appDb.bookChapterDao.getChapterList(picked.bookUrl)
                }
                if (chapters.isEmpty()) {
                    bindBookMeta()
                    showEmpty(getString(R.string.breakdown_chapter_not_cached))
                } else {
                    bindChapter(chapters.first())
                }
            }
        }
    }

    private fun showChapterPicker() {
        val book = currentBook ?: run {
            ctx.toastOnUi(R.string.breakdown_floating_pick_book)
            return
        }
        scope.launch {
            val chapters = withContext(IO) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }
            if (chapters.isEmpty()) {
                ctx.toastOnUi(R.string.breakdown_chapter_not_cached)
                return@launch
            }
            activity.selector(
                ctx.getString(R.string.breakdown_floating_pick_chapter),
                chapters.map { it.title }
            ) { _, _, index ->
                scope.launch { bindChapter(chapters[index]) }
            }
        }
    }

    private suspend fun bindChapter(chapter: BookChapter) {
        currentChapter = chapter
        bindBookMeta()
        withContext(Main) {
            binding.tvFloatingChapterName.text = chapter.title.ifBlank {
                ctx.getString(R.string.breakdown_status_none)
            }
        }
        val content = currentBook?.let {
            withContext(IO) { BookHelp.getContent(it, chapter) }
        }
        if (content.isNullOrBlank()) {
            currentContent = ""
            showEmpty(getString(R.string.breakdown_floating_no_content))
        } else {
            currentContent = content
            withContext(Main) {
                binding.tvFloatingContent.text = content
            }
        }
    }

    private fun bindBookMeta() {
        val book = currentBook ?: return
        binding.tvFloatingBookName.text = buildString {
            append(book.name)
            if (book.author.isNotBlank()) {
                append(" · ").append(book.author)
            }
        }
    }

    private fun showEmpty(tip: String) {
        binding.tvFloatingContent.text = tip
    }

    private fun getString(res: Int, vararg args: Any): String = ctx.getString(res, *args)

    /* =============================== 收录素材 =============================== */

    private fun collectSelectedText() {
        val book = currentBook
        val chapter = currentChapter
        if (book == null || chapter == null) {
            ctx.toastOnUi(R.string.breakdown_floating_pick_book)
            return
        }
        if (currentContent.isEmpty()) {
            ctx.toastOnUi(R.string.breakdown_floating_no_content)
            return
        }
        val tv = binding.tvFloatingContent
        val start = Selection.getSelectionStart(tv.text)
        val end = Selection.getSelectionEnd(tv.text)
        if (start < 0 || end < 0 || start == end) {
            ctx.toastOnUi(R.string.breakdown_floating_no_selection)
            return
        }
        val a = minOf(start, end)
        val b = maxOf(start, end)
        val selected = currentContent.substring(a, b).trim()
        if (selected.isEmpty()) {
            ctx.toastOnUi(R.string.breakdown_floating_no_selection)
            return
        }
        showCollectDialog(book, chapter, a, b, selected)
    }

    private fun showCollectDialog(
        book: Book,
        chapter: BookChapter,
        start: Int,
        end: Int,
        content: String
    ) {
        val pad = 12.dpToPx()
        val noteEdit = ThemeEditText(ctx, null).apply {
            hint = ctx.getString(R.string.breakdown_floating_note_hint)
            setSingleLine(false)
            minLines = 2
        }
        val tagsEdit = ThemeEditText(ctx, null).apply {
            hint = ctx.getString(R.string.breakdown_floating_tags_hint)
            setSingleLine()
        }
        val contentPreview = android.widget.TextView(ctx).apply {
            text = content
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
            setTextColor(ctx.primaryTextColor)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(contentPreview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(noteEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
            addView(tagsEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }
        activity.alert(R.string.breakdown_floating_collect) {
            setCustomView(container)
            okButton {
                val note = noteEdit.text?.toString().orEmpty().trim()
                val tagsRaw = tagsEdit.text?.toString().orEmpty().trim()
                val tags = tagsRaw.split("[,，]".toRegex())
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                saveMaterial(book, chapter, start, end, content, note, tags)
            }
            cancelButton()
        }.show()
    }

    private fun saveMaterial(
        book: Book,
        chapter: BookChapter,
        start: Int,
        end: Int,
        content: String,
        note: String,
        tags: List<String>
    ) = scope.launch {
        val material = Material(
            bookName = book.name,
            bookAuthor = book.author,
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            chapterPos = start,
            chapterPosEnd = end,
            chapterName = chapter.title,
            content = content,
            note = note,
            tags = tags
        )
        withContext(IO) {
            appDb.materialDao.insert(material)
        }
        MaterialHelper.notifyChanged()
        ctx.toastOnUi(R.string.breakdown_floating_collect_done)
    }
}
