package io.legado.app.ui.main.material

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.data.entities.Material
import io.legado.app.ui.book.material.MaterialTagViews
import io.legado.app.utils.dpToPx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 素材库多视图 Adapter(时间线/书籍/标签)
 */
class MaterialLibraryAdapter(context: Context) :
    RecyclerView.Adapter<MaterialLibraryAdapter.MyViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MATERIAL = 1
        const val VIEW_TYPE_BOOK = 2
        const val VIEW_TYPE_TAG_PANEL = 3
    }

    private val layoutInflater = LayoutInflater.from(context)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var items: List<MaterialViewItem> = emptyList()
        private set

    /** 多选状态 */
    var selectionMode = false
    val selectedIds = linkedSetOf<Long>()

    var callBack: CallBack? = null

    fun setItems(newItems: List<MaterialViewItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    val selectableCount: Int
        get() = items.filterIsInstance<MaterialViewItem.MaterialItem>().size

    fun selectedMaterials(): List<Material> {
        return items.filterIsInstance<MaterialViewItem.MaterialItem>()
            .filter { selectedIds.contains(it.material.id) }
            .map { it.material }
    }

    fun toggleSelect(material: Material): Boolean {
        if (selectedIds.contains(material.id)) {
            selectedIds.remove(material.id)
        } else {
            selectedIds.add(material.id)
        }
        if (selectedIds.isEmpty()) {
            selectionMode = false
        }
        notifyDataSetChanged()
        return selectionMode
    }

    fun selectAll(selectAll: Boolean) {
        selectedIds.clear()
        if (selectAll) {
            items.filterIsInstance<MaterialViewItem.MaterialItem>()
                .forEach { selectedIds.add(it.material.id) }
        } else {
            selectionMode = false
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is MaterialViewItem.Header -> VIEW_TYPE_HEADER
            is MaterialViewItem.MaterialItem -> VIEW_TYPE_MATERIAL
            is MaterialViewItem.BookRow -> VIEW_TYPE_BOOK
            is MaterialViewItem.TagPanel -> VIEW_TYPE_TAG_PANEL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val layout = when (viewType) {
            VIEW_TYPE_HEADER -> R.layout.item_material_header
            VIEW_TYPE_BOOK -> R.layout.item_material_book
            VIEW_TYPE_TAG_PANEL -> R.layout.view_material_tags
            else -> R.layout.item_material_card
        }
        return MyViewHolder(layoutInflater.inflate(layout, parent, false), viewType)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        when (val item = items[position]) {
            is MaterialViewItem.Header -> {
                holder.tvHeader?.text = item.title
            }

            is MaterialViewItem.MaterialItem -> {
                holder.upMaterial(item.material)
            }

            is MaterialViewItem.BookRow -> {
                holder.tvCover?.text = item.bookName.take(1)
                holder.tvName?.text = item.bookName
                val author = item.bookAuthor.ifBlank { "佚名" }
                holder.tvInfo?.text = "$author · ${item.count} 条"
                holder.itemView.setOnClickListener {
                    callBack?.onBookClick(item.bookName, item.bookAuthor)
                }
            }

            is MaterialViewItem.TagPanel -> {
                holder.upTagPanel(item.tags)
            }
        }
    }

    inner class MyViewHolder(view: View, viewType: Int) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView? = view.findViewById(R.id.tv_header)
        val tvCover: TextView? = view.findViewById(R.id.tv_cover)
        val tvName: TextView? = view.findViewById(R.id.tv_name)
        val tvInfo: TextView? = view.findViewById(R.id.tv_info)
        private val tvContent: TextView? = view.findViewById(R.id.tv_content)
        private val tvNote: TextView? = view.findViewById(R.id.tv_note)
        private val tvMeta: TextView? = view.findViewById(R.id.tv_meta)
        private val flexTags: FlexboxLayout? = view.findViewById(R.id.flex_tags)
        private val flexTagPanel: FlexboxLayout? = view.findViewById(R.id.flex_tag_panel)

        fun upMaterial(material: Material) {
            tvContent?.text = material.content
            val isSelected = selectionMode && selectedIds.contains(material.id)
            itemView.alpha = if (isSelected) 0.5f else 1f
            if (material.note.isNotBlank()) {
                tvNote?.text = "✎ ${material.note}"
                tvNote?.visibility = View.VISIBLE
            } else {
                tvNote?.visibility = View.GONE
            }
            val meta = buildString {
                append("《").append(material.bookName).append("》")
                if (material.chapterName.isNotBlank()) {
                    append(" · ").append(material.chapterName)
                }
                append(" · ").append(dateFmt.format(Date(material.createTime)))
            }
            tvMeta?.text = meta
            val flex = flexTags ?: return
            flex.removeAllViews()
            if (material.tags.isEmpty()) {
                flex.visibility = View.GONE
            } else {
                flex.visibility = View.VISIBLE
                material.tags.forEach { tag ->
                    val pill = MaterialTagViews.newPill(flex.context, tag) {
                        callBack?.onTagClick(tag)
                    }
                    pill.layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 8.dpToPx(), 4.dpToPx()) }
                    flex.addView(pill)
                }
            }
            itemView.setOnClickListener {
                callBack?.onMaterialClick(material)
            }
            itemView.setOnLongClickListener {
                callBack?.onMaterialLongClick(material)
                true
            }
        }

        fun upTagPanel(tags: List<Pair<String, Int>>) {
            val flex = flexTagPanel ?: return
            flex.removeAllViews()
            if (tags.isEmpty()) {
                flex.visibility = View.GONE
                return
            }
            flex.visibility = View.VISIBLE
            tags.forEach { (tag, count) ->
                val pill = MaterialTagViews.newPill(flex.context, "$tag · $count") {
                    callBack?.onTagClick(tag)
                }
                pill.layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8.dpToPx(), 6.dpToPx()) }
                flex.addView(pill)
            }
        }
    }

    interface CallBack {
        fun onMaterialClick(material: Material)
        fun onMaterialLongClick(material: Material)
        fun onBookClick(bookName: String, bookAuthor: String)
        fun onTagClick(tag: String)
    }

}
