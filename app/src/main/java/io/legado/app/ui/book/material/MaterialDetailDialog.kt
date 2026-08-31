package io.legado.app.ui.book.material

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Material
import io.legado.app.help.material.MaterialHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogMaterialBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 素材详情/编辑底部弹层
 */
class MaterialDetailDialog() : BaseDialogFragment(R.layout.dialog_material, true) {

    companion object {

        fun newInstance(id: Long): MaterialDetailDialog {
            return MaterialDetailDialog().apply {
                arguments = Bundle().apply {
                    putLong("id", id)
                }
            }
        }
    }

    private val binding by viewBinding(DialogMaterialBinding::bind)
    private var material: Material? = null
    private val tags = arrayListOf<String>()
    private val dateFmt by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

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
        val id = arguments?.getLong("id") ?: -1L
        if (id <= 0) {
            dismiss()
            return
        }
        lifecycleScope.launch {
            val m = withContext(IO) {
                appDb.materialDao.get(id)
            }
            if (m == null) {
                dismiss()
            } else {
                material = m
                tags.clear()
                tags.addAll(m.tags)
                upView(m)
            }
        }
    }

    private fun upView(m: Material) {
        binding.tvBook.text = buildString {
            append("《").append(m.bookName).append("》")
            if (m.chapterName.isNotBlank()) {
                append(" · ").append(m.chapterName)
            }
        }
        binding.tvMeta.text = getString(
            R.string.material_meta_info,
            dateFmt.format(Date(m.createTime)),
            m.content.length
        )
        binding.editContent.setText(m.content)
        binding.editNote.setText(m.note)
        binding.tvFavorite.text =
            if (m.favorite) getString(R.string.material_starred) else getString(R.string.material_star)
        binding.tvFavorite.setOnClickListener {
            m.favorite = !m.favorite
            binding.tvFavorite.text =
                if (m.favorite) getString(R.string.material_starred) else getString(R.string.material_star)
        }
        upTags()
        binding.tvAddTag.setOnClickListener { showTagPicker() }
        binding.tvJump.setOnClickListener { jumpToSource() }
        binding.tvDelete.setOnClickListener { deleteMaterial() }
        binding.tvSave.setOnClickListener { saveMaterial() }
    }

    private fun upTags() {
        val context = context ?: return
        binding.flexTags.removeAllViews()
        for (tag in tags) {
            val pill = MaterialTagViews.newPill(context, tag, closable = true) {
                tags.remove(tag)
                upTags()
            }
            pill.layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 8.dpToPx(), 6.dpToPx())
            }
            binding.flexTags.addView(pill)
        }
    }

    /**
     * 标签选择器:现有标签点选 + 输入新标签(支持逗号/空格/顿号分隔)
     */
    private fun showTagPicker() {
        val context = context ?: return
        lifecycleScope.launch {
            val existing = withContext(IO) {
                MaterialHelper.aggregateTags(appDb.materialDao.getActive()).map { it.first }
            }
            val selected = tags.toMutableSet()
            val editText = ThemeEditText(context).apply {
                hint = getString(R.string.material_input_tag)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                val pad = 16.dpToPx()
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val flex = FlexboxLayout(context).apply {
                val pad = 8.dpToPx()
                setPadding(pad, pad, pad, pad)
            }
            existing.forEach { tagName ->
                val pill = MaterialTagViews.newPill(
                    context, tagName, selected = tagName in selected
                )
                restylePill(pill, tagName, tagName in selected)
                pill.setOnClickListener {
                    if (tagName in selected) {
                        selected.remove(tagName)
                    } else {
                        selected.add(tagName)
                    }
                    restylePill(pill, tagName, tagName in selected)
                }
                pill.tag = tagName
                pill.layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 8.dpToPx(), 6.dpToPx())
                }
                flex.addView(pill)
            }
            val scrollView = ScrollView(context)
            scrollView.addView(flex)
            val container = LinearLayout(context)
            container.orientation = LinearLayout.VERTICAL
            container.addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                scrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                ).apply { weight = 1f }
            )
            alert(R.string.material_add_tag) {
                setCustomView(container)
                okButton {
                    editText.text?.toString()?.split(',', '，', ' ', '、')
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.forEach { selected.add(it) }
                    tags.clear()
                    tags.addAll(selected)
                    upTags()
                }
                cancelButton()
            }.show()
        }
    }

    /**
     * 刷新选择器中单个药丸的选中态
     */
    private fun restylePill(pill: TextView, tag: String, isSelected: Boolean) {
        val color = MaterialHelper.tagColor(tag)
        pill.setTextColor(if (isSelected) Color.WHITE else color)
        pill.background = GradientDrawable().apply {
            cornerRadius = 999.dpToPx().toFloat()
            setColor(if (isSelected) color else Color.TRANSPARENT)
            setStroke(1.dpToPx(), color)
        }
    }

    private fun jumpToSource() {
        val m = material ?: return
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(m.bookName, m.bookAuthor)
            }
            if (book == null) {
                context?.toastOnUi(R.string.material_book_not_found)
            } else {
                context?.startActivityForBook(book) {
                    putExtra("index", m.chapterIndex)
                    putExtra("chapterPos", m.chapterPos)
                    putExtra("materialId", m.id)
                }
            }
        }
    }

    private fun deleteMaterial() {
        val m = material ?: return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(IO) {
                appDb.materialDao.moveToTrash(listOf(m.id), now)
            }
            MaterialHelper.notifyChanged()
            context?.toastOnUi(R.string.material_moved_to_trash)
            dismiss()
        }
    }

    private fun saveMaterial() {
        val m = material ?: return
        val content = binding.editContent.text?.toString() ?: ""
        if (content.isBlank()) {
            toastOnUi(R.string.material_content_empty)
            return
        }
        m.content = content
        m.note = binding.editNote.text?.toString() ?: ""
        m.tags = tags.toList()
        m.updateTime = System.currentTimeMillis()
        lifecycleScope.launch {
            withContext(IO) {
                appDb.materialDao.update(m)
            }
            MaterialHelper.notifyChanged()
            context?.toastOnUi(R.string.material_saved)
            dismiss()
        }
    }

}
