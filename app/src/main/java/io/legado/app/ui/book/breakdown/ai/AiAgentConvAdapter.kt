package io.legado.app.ui.book.breakdown.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.AiAgentConv
import io.legado.app.databinding.ItemAiAgentConvBinding
import io.legado.app.databinding.ItemAiAgentConvHeaderBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.ColorUtils
import java.util.Calendar

/**
 * 会话抽屉列表:按「今天 / 更早」分组,条目一行标题 + 类型与状态小标签;
 * 长按删除(回调内二次确认),当前会话浅色高亮。
 */
class AiAgentConvAdapter(
    private val context: Context,
    private val callBack: CallBack
) : ListAdapter<AiAgentConvAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CONV = 1
    }

    var items: List<AiAgentConv> = emptyList()
        set(value) {
            field = value
            submitList(buildRows(value))
        }

    var selectedId: Long = -1L
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private val inflater = LayoutInflater.from(context)
    private val todayLabel = context.getString(R.string.ai_agent_today)
    private val earlierLabel = context.getString(R.string.ai_agent_earlier)

    private fun buildRows(convs: List<AiAgentConv>): List<Row> {
        val rows = ArrayList<Row>(convs.size + 2)
        var lastLabel: String? = null
        convs.forEach { c ->
            val label = if (isToday(c.updateTime)) todayLabel else earlierLabel
            if (label != lastLabel) {
                rows.add(Row.Header(label))
                lastLabel = label
            }
            rows.add(Row.Conv(c))
        }
        return rows
    }

    private fun isToday(time: Long): Boolean {
        val cal = Calendar.getInstance()
        val nowYear = cal.get(Calendar.YEAR)
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = time
        return cal.get(Calendar.YEAR) == nowYear && cal.get(Calendar.DAY_OF_YEAR) == nowDay
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_CONV

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemAiAgentConvHeaderBinding.inflate(inflater, parent, false))
        } else {
            ConvVH(ItemAiAgentConvBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.Conv -> (holder as ConvVH).bind(row.conv)
        }
    }

    class HeaderVH(b: ItemAiAgentConvHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        private val tv = b.tvHeader
        fun bind(row: Row.Header) {
            tv.text = row.label
        }
    }

    inner class ConvVH(private val b: ItemAiAgentConvBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: AiAgentConv) {
            val selected = c.id == selectedId
            b.root.isSelected = selected
            (b.root.background.mutate() as GradientDrawable).setColor(
                if (selected) ColorUtils.withAlpha(context.accentColor, 0.12f)
                else Color.TRANSPARENT
            )
            b.tvTitle.setTextColor(context.primaryTextColor)
            b.tvTitle.text = c.title.ifBlank { context.getString(R.string.ai_agent_conv_new_hint) }
            b.tvKind.text = when (c.kind) {
                AiAgentConv.TASK_BREAKDOWN -> context.getString(R.string.ai_agent_conv_kind_breakdown)
                AiAgentConv.TASK_OTHER -> context.getString(R.string.ai_agent_conv_kind_task)
                else -> context.getString(R.string.ai_agent_conv_kind_chat)
            }
            b.tvKind.setTextColor(context.accentColor)
            (b.tvKind.background.mutate() as GradientDrawable).setColor(
                ColorUtils.withAlpha(context.accentColor, 0.10f)
            )
            val stateText = when (c.state) {
                AiAgentConv.STATE_STREAMING -> context.getString(R.string.ai_agent_status_streaming_short)
                AiAgentConv.STATE_PARSING -> context.getString(R.string.ai_agent_status_parsing_short)
                else -> null
            }
            if (stateText != null) {
                b.tvState.visibility = View.VISIBLE
                b.tvState.text = stateText
                b.tvState.setTextColor(context.accentColor)
            } else {
                b.tvState.visibility = View.GONE
            }
            b.root.setOnClickListener { callBack.onConvClick(c) }
            b.root.setOnLongClickListener {
                callBack.onConvDelete(c)
                true
            }
        }
    }

    sealed class Row {
        class Header(val label: String) : Row()
        class Conv(val conv: AiAgentConv) : Row()
    }

    interface CallBack {
        fun onConvClick(conv: AiAgentConv)
        fun onConvDelete(conv: AiAgentConv)
    }

    object DIFF : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
            a is Row.Header && b is Row.Header -> a.label == b.label
            a is Row.Conv && b is Row.Conv -> a.conv.id == b.conv.id
            else -> false
        }

        override fun areContentsTheSame(a: Row, b: Row): Boolean = when {
            a is Row.Header && b is Row.Header -> a.label == b.label
            a is Row.Conv && b is Row.Conv -> a.conv == b.conv
            else -> false
        }
    }
}
