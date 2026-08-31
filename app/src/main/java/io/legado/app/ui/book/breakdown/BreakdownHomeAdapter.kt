package io.legado.app.ui.book.breakdown

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.dao.BreakdownWithProgress
import io.legado.app.utils.dpToPx

/**
 * 拆书房档案流
 */
class BreakdownHomeAdapter(context: Context) :
    RecyclerView.Adapter<BreakdownHomeAdapter.MyViewHolder>() {

    companion object {
        const val STATUS_DRAFT = 1
        const val STATUS_CONFIRMED = 2
    }

    private val layoutInflater = LayoutInflater.from(context)
    var items: List<BreakdownWithProgress> = emptyList()
        private set

    var callBack: CallBack? = null

    fun setItems(newItems: List<BreakdownWithProgress>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder =
        MyViewHolder(layoutInflater.inflate(R.layout.item_breakdown_card, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) = holder.up(items[position])

    inner class MyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCover: TextView = view.findViewById(R.id.tv_cover)
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvAuthor: TextView = view.findViewById(R.id.tv_author)
        private val tvCategory: TextView = view.findViewById(R.id.tv_category)
        private val tvAchievement: TextView = view.findViewById(R.id.tv_achievement)
        private val tvProgress: TextView = view.findViewById(R.id.tv_progress)
        private val progress: android.widget.ProgressBar = view.findViewById(R.id.progress)
        private val tvAiBadge: TextView = view.findViewById(R.id.tv_ai_badge)

        fun up(wi: BreakdownWithProgress) {
            val b = wi.breakdown
            tvCover.text = b.bookName.take(1)
            tvName.text = b.bookName
            tvAuthor.text = b.bookAuthor.ifBlank { itemView.context.getString(R.string.material_unknown_chapter) }
            tvCategory.text = b.category
            tvCategory.visibility = if (b.category.isBlank()) View.GONE else View.VISIBLE
            tvAchievement.text = b.achievement
            tvAchievement.visibility = if (b.achievement.isBlank()) View.GONE else View.VISIBLE
            val total = wi.chapterCount
            val done = wi.confirmedCount + wi.draftCount
            progress.max = total.coerceAtLeast(1)
            progress.progress = done
            tvProgress.text = itemView.context.getString(
                R.string.breakdown_progress, done, total
            )
            tvAiBadge.visibility = if (wi.draftCount > 0) View.VISIBLE else View.GONE
            itemView.setOnClickListener { callBack?.onBreakdownClick(b) }
            itemView.setOnLongClickListener { callBack?.onBreakdownLongClick(b); true }
        }
    }

    interface CallBack {
        fun onBreakdownClick(breakdown: io.legado.app.data.entities.BookBreakdown)
        fun onBreakdownLongClick(breakdown: io.legado.app.data.entities.BookBreakdown)
    }
}