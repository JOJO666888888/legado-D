package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.book.material.MaterialTagViews
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogSegmentEditBinding
import io.legado.app.lib.theme.view.ThemeEditText
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 段落编辑底部弹层:行号区间(实时预览)+ 标签(模板词库单选/自定义)+ 三栏内容 + 跳原文/删除/保存
 */
class SegmentEditDialog() : BaseDialogFragment(R.layout.dialog_segment_edit, true) {

    companion object {
        fun newInstance(breakdownId: Long, chapterIndex: Int, segmentId: Long): SegmentEditDialog {
            return SegmentEditDialog().apply {
                arguments = Bundle().apply {
                    putLong("breakdownId", breakdownId)
                    putInt("chapterIndex", chapterIndex)
                    putLong("segmentId", segmentId)
                }
            }
        }
    }

    private val binding by viewBinding(DialogSegmentEditBinding::bind)
    private val breakdownId get() = arguments?.getLong("breakdownId") ?: -1L
    private val chapterIndex get() = arguments?.getInt("chapterIndex") ?: 0
    private val segmentId get() = arguments?.getLong("segmentId") ?: 0L

    private var book: Book? = null
    private var chapter: BookChapter? = null
    private var breakdown: io.legado.app.data.entities.BookBreakdown? = null
    private var segment: BreakdownSegment? = null
    private var lines: Array<String>? = null
    private val labelWords = arrayListOf<String>()
    private var selectedLabel = ""

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val attr = attributes
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (breakdownId <= 0 || chapterIndex < 0) {
            dismiss()
            return
        }
        lifecycleScope.launch {
            val ctx = withContext(IO) {
                val bd = appDb.bookBreakdownDao.get(breakdownId)
                val bk = bd?.bookUrl?.takeIf { it.isNotBlank() }?.let { appDb.bookDao.getBook(it) }
                val ch = bk?.let { appDb.bookChapterDao.getChapter(it.bookUrl, chapterIndex) }
                val lines = if (bk != null && ch != null) BreakdownHelper.getPurifiedLines(bk, ch) else null
                val labels = bd?.templateId?.takeIf { it > 0 }
                    ?.let { appDb.breakdownTemplateDao.get(it)?.segmentLabels }.orEmpty()
                val seg = if (segmentId > 0) appDb.breakdownSegmentDao.get(segmentId) else null
                DataHolder(bd, bk, ch, lines, labels, seg)
            }
            breakdown = ctx.bd
            book = ctx.bk
            chapter = ctx.ch
            lines = ctx.lines
            selectedLabel = ""
            if (ctx.labels.isNotEmpty()) {
                labelWords.clear()
                labelWords.addAll(ctx.labels)
            }
            segment = ctx.seg
            initUi(ctx)
        }
    }

    private data class DataHolder(
        val bd: io.legado.app.data.entities.BookBreakdown?,
        val bk: Book?,
        val ch: BookChapter?,
        val lines: Array<String>?,
        val labels: List<String>,
        val seg: BreakdownSegment?
    )

    private fun initUi(ctx: DataHolder) {
        val seg = ctx.seg
        if (seg != null) {
            binding.editStartLine.setText(seg.startLine.toString())
            binding.editEndLine.setText(seg.endLine.toString())
            binding.editContentSummary.setText(seg.contentSummary)
            binding.editRhythmNote.setText(seg.rhythmNote)
            binding.editHighlights.setText(seg.highlights)
            selectedLabel = seg.label
        }
        binding.tvDelete.visibility = if (seg != null) View.VISIBLE else View.GONE
        upLabels()
        upPreview(null, null)
        binding.editStartLine.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                upPreview(s?.toString()?.toIntOrNull(), binding.editEndLine.text?.toString()?.toIntOrNull())
            }
        })
        binding.editEndLine.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                upPreview(binding.editStartLine.text?.toString()?.toIntOrNull(), s?.toString()?.toIntOrNull())
            }
        })
        binding.tvImportMaterial.setOnClickListener { importFromMaterial() }
        binding.tvJump.setOnClickListener { jumpToSource() }
        binding.tvDelete.setOnClickListener {
            val segToDelete = segment ?: return@setOnClickListener
            lifecycleScope.launch {
                withContext(IO) {
                    appDb.breakdownSegmentDao.deleteByIds(listOf(segToDelete.id))
                }
                BreakdownHelper.notifyChanged()
                toastOnUi(R.string.breakdown_segment_saved)
                dismiss()
            }
        }
        binding.tvSave.setOnClickListener { save() }
    }

    private fun upPreview(start: Int?, end: Int?) {
        val lines = lines ?: run {
            binding.tvPreview.setText(R.string.breakdown_jump_not_found)
            return
        }
        val startLine = start ?: binding.editStartLine.text?.toString()?.toIntOrNull() ?: 0
        val endLine = end ?: binding.editEndLine.text?.toString()?.toIntOrNull() ?: 0
        if (startLine in 1..lines.size && endLine in startLine..lines.size) {
            val text = BreakdownHelper.rangeText(lines, startLine, endLine)
            binding.tvPreview.text = text.ifEmpty { getString(R.string.breakdown_line_range, startLine, endLine) }
            binding.tvPreview.visibility = View.VISIBLE
        } else {
            binding.tvPreview.text = getString(
                R.string.breakdown_line_range,
                startLine.coerceAtLeast(0), endLine.coerceAtLeast(0)
            )
        }
    }

    private fun upLabels() {
        val context = context ?: return
        binding.flexLabels.removeAllViews()
        labelWords.forEach { word ->
            val pill = MaterialTagViews.newPill(
                context, word, selected = word == selectedLabel
            ) {
                selectedLabel = word
                upLabels()
            }
            pill.layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8.dpToPx(), 6.dpToPx()) }
            binding.flexLabels.addView(pill)
        }
    }

    private fun save() {
        val lines = lines
        if (lines == null) {
            toastOnUi(R.string.breakdown_jump_not_found)
            return
        }
        val startLine = binding.editStartLine.text?.toString()?.trim()?.toIntOrNull() ?: 1
        val endLine = binding.editEndLine.text?.toString()?.trim()?.toIntOrNull() ?: startLine
        if (startLine < 1 || startLine > lines.size || endLine < startLine || endLine > lines.size) {
            toastOnUi(getString(R.string.breakdown_line_range, startLine, endLine))
            return
        }
        val old = segment
        val base = old ?: BreakdownSegment(chapterId = 0L, sortOrder = 0)
        var updated = base.copy(
            startLine = startLine,
            endLine = endLine,
            label = selectedLabel,
            contentSummary = binding.editContentSummary.text?.toString()?.trim().orEmpty(),
            rhythmNote = binding.editRhythmNote.text?.toString()?.trim().orEmpty(),
            highlights = binding.editHighlights.text?.toString()?.trim().orEmpty(),
            updateTime = System.currentTimeMillis()
        )
        updated = BreakdownHelper.refreshSegmentPos(updated, lines)
        lifecycleScope.launch {
            val chapterId = chapterIdOf()
            val newOrder = if (old == null) {
                withContext(IO) { (appDb.breakdownSegmentDao.getByChapter(chapterId).size + 1) }
            } else old.sortOrder
            updated = updated.copy(chapterId = chapterId, sortOrder = newOrder)
            withContext(IO) {
                appDb.breakdownSegmentDao.upsert(updated)
            }
            BreakdownHelper.notifyChanged()
            toastOnUi(R.string.breakdown_segment_saved)
            dismiss()
        }
    }

    /** 确保章记录存在并返回 id(保存段落前调用) */
    private suspend fun chapterIdOf(): Long {
        val bd = breakdown ?: return -1L
        val ch = chapter ?: return -1L
        return withContext(IO) {
            appDb.breakdownChapterDao.getByBreakdownAndIndex(bd.id, ch.index)?.id
                ?: run {
                    val new = io.legado.app.data.entities.BreakdownChapter(
                        breakdownId = bd.id,
                        chapterIndex = ch.index,
                        chapterName = ch.title
                    )
                    appDb.breakdownChapterDao.upsert(new).firstOrNull() ?: -1L
                }
        }
    }

    private fun jumpToSource() {
        val bk = book ?: run {
            toastOnUi(R.string.breakdown_book_not_found)
            return
        }
        val ch = chapter ?: return
        val lines = lines
        val seg = segment
        var pos = seg?.startPos ?: 0
        if (seg != null && lines != null) {
            val expect = BreakdownHelper.rangeText(lines, seg.startLine, seg.endLine)
            if (expect.isNotEmpty()) {
                val full = lines.joinToString("\n")
                BreakdownHelper.resolveRange(full, expect, seg.startPos, seg.endPos)?.let {
                    pos = it[0]
                }
            }
        }
        requireContext().startActivityForBook(bk) {
            putExtra("index", ch.index)
            putExtra("chapterPos", pos)
        }
    }

    /** 从本章素材导入区间(逆公式反算行号) */
    private fun importFromMaterial() {
        val bd = breakdown ?: return
        lifecycleScope.launch {
            val materials = withContext(IO) {
                appDb.materialDao.getActiveByChapter(bd.bookName, bd.bookAuthor, chapterIndex)
            }
            if (materials.isEmpty()) {
                toastOnUi(R.string.material_empty)
                return@launch
            }
            requireContext().selector(
                getString(R.string.breakdown_import_material),
                materials.map { it.content.take(30) }
            ) { _, _, index ->
                val m = materials[index]
                val lines = lines
                if (lines == null) {
                    toastOnUi(R.string.breakdown_jump_not_found)
                    return@selector
                }
                val (s, e) = BreakdownHelper.posRangeToLines(lines, m.chapterPos, m.chapterPosEnd)
                binding.editStartLine.setText(s.toString())
                binding.editEndLine.setText(e.toString())
                upPreview(s, e)
            }
        }
    }
}