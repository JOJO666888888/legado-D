package io.legado.app.ui.book.breakdown

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.breakdown.BreakdownHelper

/**
 * 档案页章节行
 */
class BreakdownChapterAdapter(context: Context) :
    RecyclerView.Adapter<BreakdownChapterAdapter.MyViewHolder>() {

    data class ChapterRow(
        val chapterIndex: Int,
        val name: String,
        val status: Int,
        val aiModel: String,
        val segmentCount: Int
    )

    private val layoutInflater = LayoutInflater.from(context)
    var items: List<ChapterRow> = emptyList()
        private set

    var callBack: CallBack? = null

    fun setItems(newItems: List<ChapterRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder =
        MyViewHolder(layoutInflater.inflate(R.layout.item_breakdown_chapter, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) = holder.up(items[position])

    inner class MyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_chapter_name)
        private val tvStatus: TextView = view.findViewById(R.id.tv_status)
        private val tvCount: TextView = view.findViewById(R.id.tv_segment_count)

        fun up(row: ChapterRow) {
            tvName.text = row.name
            val context = itemView.context
            val textColorWhite = context.resources.getColor(android.R.color.white)
            tvStatus.text = when (row.status) {
                BreakdownHelper.STATUS_CONFIRMED -> context.getString(R.string.breakdown_status_confirmed)
                BreakdownHelper.STATUS_DRAFT -> if (row.aiModel.isBlank())
                    context.getString(R.string.breakdown_status_draft)
                else context.getString(R.string.breakdown_ai_draft_badge)
                else -> context.getString(R.string.breakdown_status_none)
            }
            tvStatus.background = when (row.status) {
                BreakdownHelper.STATUS_CONFIRMED -> context.getDrawable(R.drawable.shape_badge_confirmed)
                BreakdownHelper.STATUS_DRAFT -> context.getDrawable(R.drawable.shape_badge_draft)
                else -> context.getDrawable(R.drawable.shape_badge_status_none)
            }
            tvStatus.setTextColor(
                if (row.status == BreakdownHelper.STATUS_CONFIRMED || row.status == BreakdownHelper.STATUS_DRAFT)
                    textColorWhite
                else context.resources.getColor(R.color.primaryText)
            )
            tvCount.text = context.getString(R.string.breakdown_segment_count, row.segmentCount)
            itemView.setOnClickListener { callBack?.onChapterClick(row.chapterIndex) }
        }
    }

    interface CallBack {
        fun onChapterClick(chapterIndex: Int)
    }
}