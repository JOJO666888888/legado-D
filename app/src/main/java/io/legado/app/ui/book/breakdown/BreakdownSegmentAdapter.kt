package io.legado.app.ui.book.breakdown

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.BreakdownSegment
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.ui.widget.SelectActionBar

/**
 * 章节拆解页段落流(含章末总结卡 header + 段落卡)
 */
class BreakdownSegmentAdapter(context: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(),
    SelectActionBar.CallBack {

    companion object {
        const val VIEW_TYPE_SUMMARY = 0
        const val VIEW_TYPE_SEGMENT = 1
    }

    private val layoutInflater = LayoutInflater.from(context)
    var segments: List<BreakdownSegment> = emptyList()
        private set

    /** 章末总结卡 */
    var summaryView: View? = null

    /** 本章是否 AI 生成(草稿徽标显示) */
    var isAiGenerated = false

    /** 多选状态 */
    var selectionMode = false
    val selectedIds = linkedSetOf<Long>()

    var callBack: CallBack? = null

    fun setSegments(list: List<BreakdownSegment>) {
        segments = list
        notifyDataSetChanged()
    }

    fun validateSelection() {
        val live = segments.map { it.id }.toSet()
        selectedIds.retainAll(live)
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        validateSelection()
        notifyDataSetChanged()
    }

    private fun toggleAll(selectAll: Boolean) {
        selectedIds.clear()
        if (selectAll) {
            segments.forEach { selectedIds.add(it.id) }
        } else {
            exitSelection()
        }
        notifyDataSetChanged()
    }

    override fun selectAll(selectAll: Boolean) {
        toggleAll(selectAll)
    }

    val selectableCount get() = segments.size

    override fun revertSelection() {
        exitSelection()
    }

    override fun onClickSelectBarMainAction() {
        callBack?.onSelectionMainAction()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_SUMMARY else VIEW_TYPE_SEGMENT
    }

    override fun getItemCount(): Int = segments.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SUMMARY) {
            SummaryViewHolder(layoutInflater.inflate(R.layout.item_breakdown_summary, parent, false))
        } else {
            SegmentViewHolder(layoutInflater.inflate(R.layout.item_breakdown_segment, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) {
            (holder as SummaryViewHolder).bind()
        } else {
            (holder as SegmentViewHolder).bind(segments[position - 1])
        }
    }

    inner class SummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            summaryView = itemView
        }
    }

    inner class SegmentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvRange: TextView = view.findViewById(R.id.tv_range)
        private val tvLabel: TextView = view.findViewById(R.id.tv_label)
        private val tvNeedCheck: TextView = view.findViewById(R.id.tv_need_check)
        private val tvAiBadge: TextView = view.findViewById(R.id.tv_ai_badge)
        private val tvSummary: TextView = view.findViewById(R.id.tv_summary)
        private val tvRhythm: TextView = view.findViewById(R.id.tv_rhythm)
        private val tvHighlights: TextView = view.findViewById(R.id.tv_highlights)
        private val layoutActions: View = view.findViewById(R.id.layout_actions)

        fun bind(segment: BreakdownSegment) {
            val context = itemView.context
            tvRange.text = context.getString(R.string.breakdown_line_range, segment.startLine, segment.endLine)
            tvLabel.text = segment.label
            tvLabel.visibility = if (segment.label.isBlank()) View.GONE else View.VISIBLE
            tvLabel.setTextColor(BreakdownHelper.tagColor(segment.label))
            tvNeedCheck.visibility = if (segment.needCheck) View.VISIBLE else View.GONE
            tvAiBadge.visibility = if (isAiGenerated && !segment.needCheck) View.VISIBLE else View.GONE
            tvSummary.text = segment.contentSummary
            tvRhythm.text = segment.rhythmNote.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.breakdown_rhythm, it) }
            tvRhythm.visibility = if (segment.rhythmNote.isBlank()) View.GONE else View.VISIBLE
            tvHighlights.text = segment.highlights
            tvHighlights.visibility = if (segment.highlights.isBlank()) View.GONE else View.VISIBLE
            layoutActions.visibility = View.VISIBLE
            val isSelected = selectionMode && selectedIds.contains(segment.id)
            itemView.alpha = if (isSelected) 0.4f else 1f

            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelect(segment)
                } else {
                    callBack?.onSegmentClick(segment)
                }
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                }
                toggleSelect(segment)
                true
            }
            layoutActions.findViewById<View>(R.id.tv_jump)?.setOnClickListener {
                callBack?.onJumpClick(segment)
            }
            layoutActions.findViewById<View>(R.id.tv_move_up)?.setOnClickListener {
                callBack?.onMove(segment, -1)
            }
            layoutActions.findViewById<View>(R.id.tv_move_down)?.setOnClickListener {
                callBack?.onMove(segment, 1)
            }
            layoutActions.findViewById<View>(R.id.tv_delete)?.setOnClickListener {
                callBack?.onDeleteClick(segment)
            }
        }

        private fun toggleSelect(segment: BreakdownSegment) {
            if (selectedIds.contains(segment.id)) {
                selectedIds.remove(segment.id)
            } else {
                selectedIds.add(segment.id)
            }
            if (selectedIds.isEmpty()) selectionMode = false
            notifyItemChanged(bindingAdapterPosition)
            callBack?.onSelectionChanged(selectionMode)
        }
    }

    interface CallBack {
        fun onSegmentClick(segment: BreakdownSegment)
        fun onJumpClick(segment: BreakdownSegment)
        fun onMove(segment: BreakdownSegment, direction: Int)
        fun onDeleteClick(segment: BreakdownSegment)
        fun onSelectionChanged(selectionMode: Boolean)
        fun onSelectionMainAction()
    }
}