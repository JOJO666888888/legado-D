package io.legado.app.ui.book.breakdown.ai

import android.content.Context
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.AiAgentMsg
import io.legado.app.databinding.ItemAiAgentMsgBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.getCompatColor

class AiAgentMsgAdapter(
    private val context: Context,
    private val callBack: CallBack
) : ListAdapter<AiAgentMsg, AiAgentMsgAdapter.VH>(DIFF) {

    var items: List<AiAgentMsg> = emptyList()
        set(value) { field = value; submitList(value) }

    private val inflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAiAgentMsgBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemAiAgentMsgBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: AiAgentMsg) {
            val isUser = m.role == AiAgentMsg.ROLE_USER
            val banner = when (m.kind) {
                AiAgentMsg.KIND_CONTEXT_INJECT -> "\uD83D\uDCDA ${m.contextSummary.ifBlank { "上下文已注入" }}"
                AiAgentMsg.KIND_BREAKDOWN_TASK -> "\uD83E\uDDE0 拆书任务:内容参考消息详情"
                AiAgentMsg.KIND_BREAKDOWN_RESULT -> when (m.status) {
                    AiAgentMsg.STATUS_STREAMING -> "\uD83E\uDD16 AI 推理中…(流式输出)"
                    AiAgentMsg.STATUS_PARSING -> "\uD83E\uDDE9 解析 JSON + 引文锚点校验中…"
                    AiAgentMsg.STATUS_DONE -> if (m.written) "✅ 已写入章节草稿" else "⚠️ 解析完成但未写回草稿(可能该章节已确认或异常)"
                    AiAgentMsg.STATUS_CANCELLED -> "\uD83D\uDED1 已停止生成,不写入半成品数据"
                    else -> "拆书结果"
                }
                AiAgentMsg.KIND_ERROR -> "❌ 错误"
                else -> null
            }
            if (banner != null) {
                b.tvBanner.text = banner
                b.tvBanner.visibility = android.view.View.VISIBLE
                b.tvBanner.setBackgroundColor(
                    if (m.kind == AiAgentMsg.KIND_ERROR) context.getCompatColor(R.color.md_red_500)
                    else context.accentColor
                )
            } else {
                b.tvBanner.visibility = android.view.View.GONE
            }
            val params = b.bubbleCard.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.endToEnd = if (isUser) androidx.constraintlayout.widget.ConstraintSet.PARENT_ID else androidx.constraintlayout.widget.ConstraintSet.UNSET
            params.startToStart = if (isUser) androidx.constraintlayout.widget.ConstraintSet.UNSET else androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
            b.bubbleCard.layoutParams = params
            if (isUser) {
                b.bubbleCard.setCardBackgroundColor(context.accentColor)
                b.tvContent.setTextColor(android.graphics.Color.WHITE)
            } else {
                b.bubbleCard.setCardBackgroundColor(context.backgroundColor)
                b.tvContent.setTextColor(context.getCompatColor(R.color.primaryText))
            }
            b.tvContent.text = when {
                m.content.isBlank() && m.status == AiAgentMsg.STATUS_STREAMING ->
                    SpannableStringBuilder("▌").also {
                        b.tvContent.setTextColor(context.secondaryTextColor)
                    }
                else -> m.content
            }
            b.ivRole.setImageResource(
                when (m.role) {
                    AiAgentMsg.ROLE_USER -> R.drawable.ic_avatar_user
                    else -> R.drawable.ic_avatar_ai
                }
            )
            b.btnCopy.setOnClickListener { callBack.onMsgCopy(m) }
            b.btnDelete.setOnClickListener { callBack.onMsgDelete(m) }
            b.bubbleCard.setOnLongClickListener {
                callBack.onMsgCopy(m)
                true
            }
        }
    }

    interface CallBack {
        fun onMsgDelete(msg: AiAgentMsg)
        fun onMsgCopy(msg: AiAgentMsg)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AiAgentMsg>() {
            override fun areItemsTheSame(a: AiAgentMsg, b: AiAgentMsg) = a.id == b.id
            override fun areContentsTheSame(a: AiAgentMsg, b: AiAgentMsg) = a == b
        }
    }
}
