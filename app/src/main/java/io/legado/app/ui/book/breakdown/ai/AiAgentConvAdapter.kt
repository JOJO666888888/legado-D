package io.legado.app.ui.book.breakdown.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.AiAgentConv
import io.legado.app.databinding.ItemAiAgentConvBinding

class AiAgentConvAdapter(
    private val context: Context,
    private val callBack: CallBack
) : ListAdapter<AiAgentConv, AiAgentConvAdapter.VH>(DIFF) {

    var items: List<AiAgentConv> = emptyList()
        set(value) { field = value; submitList(value) }

    var selectedId: Long = -1L
        set(value) { field = value; notifyItemRangeChanged(0, itemCount) }

    private val inflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAiAgentConvBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemAiAgentConvBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: AiAgentConv) {
            b.root.isSelected = c.id == selectedId
            b.tvTitle.text = c.title.ifBlank { "新对话" }
            b.tvKind.text = when (c.kind) {
                AiAgentConv.TASK_BREAKDOWN -> "拆书任务"
                AiAgentConv.TASK_OTHER -> "其他任务"
                else -> "对话"
            }
            val stateText = when (c.state) {
                AiAgentConv.STATE_STREAMING -> " · 生成中"
                AiAgentConv.STATE_PARSING -> " · 解析中"
                else -> ""
            }
            b.tvState.text = stateText
            b.root.setOnClickListener { callBack.onConvClick(c) }
            b.btnDelete.setOnClickListener { callBack.onConvDelete(c) }
        }
    }

    interface CallBack {
        fun onConvClick(conv: AiAgentConv)
        fun onConvDelete(conv: AiAgentConv)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AiAgentConv>() {
            override fun areItemsTheSame(a: AiAgentConv, b: AiAgentConv) = a.id == b.id
            override fun areContentsTheSame(a: AiAgentConv, b: AiAgentConv) =
                a.title == b.title && a.state == b.state && a.lastError == b.lastError
        }
    }
}
