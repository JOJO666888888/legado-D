package io.legado.app.ui.book.breakdown.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.AiAgentMsg
import io.legado.app.databinding.ItemAiAgentMsgBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

/**
 * 消息气泡适配器(浅色圆角卡片风格):
 *   - 用户消息:右侧浅色强调气泡
 *   - AI 回复:左侧浅色卡片,正文按 标题/列表/代码块 层级排版
 *   - 注入上下文 / 拆书任务 / 系统与错误:带图标小标签的轻量卡片,状态用低刺激横幅标签
 */
class AiAgentMsgAdapter(
    private val context: Context,
    private val callBack: CallBack
) : ListAdapter<AiAgentMsg, AiAgentMsgAdapter.VH>(DIFF) {

    var items: List<AiAgentMsg> = emptyList()
        set(value) { field = value; submitList(value) }

    private val inflater = LayoutInflater.from(context)

    // 主题派生色(ThemeStore 动态取值,自动适配深浅模式)
    private val bgColor = context.backgroundColor
    private val surfaceColor = ColorUtils.blendColors(bgColor, context.primaryTextColor, 0.06f)
    private val codeBgColor = ColorUtils.blendColors(bgColor, context.primaryTextColor, 0.08f)
    private val userBubbleColor = ColorUtils.blendColors(bgColor, context.accentColor, 0.18f)
    private val primaryText = context.primaryTextColor
    private val secondaryText = context.secondaryTextColor
    private val accent = context.accentColor
    private val dangerText = context.getCompatColor(R.color.md_red_700)
    /** 用户/AI 正文文字:固定黑色,与浅色卡片背景形成明显对比(不随主题灰化) */
    private val textBlack = android.graphics.Color.BLACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAiAgentMsgBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemAiAgentMsgBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(m: AiAgentMsg) {
            val isUser = m.role == AiAgentMsg.ROLE_USER && m.kind == AiAgentMsg.KIND_MESSAGE
            val isTool = m.role == AiAgentMsg.ROLE_TOOL || (!isUser && (m.kind == AiAgentMsg.KIND_CONTEXT_INJECT
                || m.kind == AiAgentMsg.KIND_BREAKDOWN_TASK))
            val isError = m.kind == AiAgentMsg.KIND_ERROR
            val isThinking = m.role == AiAgentMsg.ROLE_ASSISTANT && m.thinking.isNotBlank()

            /* 角色:仅 AI 正文消息显示头像 */
            b.ivRole.visibility =
                if (!isUser && !isTool && !isError) View.VISIBLE else View.GONE
            if (b.ivRole.visibility == View.VISIBLE) {
                b.ivRole.setImageResource(R.drawable.ic_avatar_ai)
            }

            /* 卡片底色与文字色 */
            when {
                isUser -> {
                    b.bubbleCard.setCardBackgroundColor(userBubbleColor)
                    b.tvContent.setTextColor(primaryText)
                }
                isError -> {
                    b.bubbleCard.setCardBackgroundColor(
                        ColorUtils.blendColors(bgColor, dangerText, 0.08f)
                    )
                    b.tvContent.setTextColor(dangerText)
                }
                else -> {
                    b.bubbleCard.setCardBackgroundColor(surfaceColor)
                    b.tvContent.setTextColor(primaryText)
                }
            }

            /* 用户气泡贴右、按内容收缩;其余通栏卡片 */
            val lp = b.bubbleCard.layoutParams as ConstraintLayout.LayoutParams
            if (isUser) {
                lp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                lp.horizontalBias = 1f
                lp.constrainedWidth = true
            } else {
                lp.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                lp.horizontalBias = 0f
            }
            b.bubbleCard.layoutParams = lp

            /* 思考折叠区(仅 assistant 且 thinking 非空) */
            bindThinking(m, isThinking)

            /* 工具行(role=tool) */
            bindToolRow(m)

            /* 状态/类别小标签 */
            val tag = resolveTag(m)
            if (tag != null) {
                b.tvTag.visibility = View.VISIBLE
                b.tvTag.text = tag.text
                b.tvTag.setTextColor(tag.textColor)
                (b.tvTag.background.mutate() as GradientDrawable).setColor(tag.bgColor)
                setTagIcon(b.tvTag, tag.iconRes, tag.textColor)
            } else {
                b.tvTag.visibility = View.GONE
            }

            val text = when {
                m.content.isBlank() && m.status == AiAgentMsg.STATUS_STREAMING ->
                    SpannableStringBuilder("▌").also {
                        b.tvContent.setTextColor(textBlack)
                    }
                isUser -> m.content
                else -> AiMsgTextFormatter.format(m.content, codeBgColor)
            }
            b.tvContent.text = text
            /* 正文层级排版:AI 格式化(标题/列表/代码),用户原样;文字颜色由卡片分支与 XML 共同固定为黑色 */

            b.btnCopy.setOnClickListener { callBack.onMsgCopy(m) }
            b.btnDelete.setOnClickListener { callBack.onMsgDelete(m) }
            b.bubbleCard.setOnLongClickListener {
                callBack.onMsgCopy(m)
                true
            }
        }

        /** 思维折叠:默认收起;点击「已思考 · 查看 N 字」展开格式化后的思考全文 */
        private fun bindThinking(m: AiAgentMsg, visible: Boolean) {
            if (!visible) {
                b.tvThinkingToggle.visibility = View.GONE
                b.tvThinkingBody.visibility = View.GONE
                return
            }
            b.tvThinkingToggle.visibility = View.VISIBLE
            b.tvThinkingToggle.setTextColor(secondaryText)
            b.tvThinkingToggle.setText(
                context.getString(R.string.ai_agent_think_toggle, m.thinking.length)
            )
            b.tvThinkingBody.setTextColor(secondaryText)
            (b.tvThinkingBody.background.mutate() as GradientDrawable).setColor(
                ColorUtils.blendColors(bgColor, context.primaryTextColor, 0.05f)
            )
            b.tvThinkingToggle.setOnClickListener { v ->
                val show = b.tvThinkingBody.visibility != View.VISIBLE
                b.tvThinkingBody.visibility = if (show) View.VISIBLE else View.GONE
                (v as TextView).text = if (show) {
                    context.getString(R.string.ai_agent_think_collapse)
                } else {
                    context.getString(R.string.ai_agent_think_toggle, m.thinking.length)
                }
            }
            b.tvThinkingBody.text = AiMsgTextFormatter.format(m.thinking, codeBgColor)
        }

        /** 工具调用行:窄浅色组件行(✓ 调用 / ⚠ 失败),点击复制结果详情 */
        private fun bindToolRow(m: AiAgentMsg) {
            if (m.role != AiAgentMsg.ROLE_TOOL) {
                b.tvTool.visibility = View.GONE
                return
            }
            b.tvTool.visibility = View.VISIBLE
            val failed = m.content.startsWith("工具执行失败")
            b.tvTool.text = when {
                failed -> "⚠ 工具:${m.content.take(60)}"
                else -> {
                    val name = m.toolCallId.ifBlank { "" }.let { "($it)" }
                    "✓ 工具调用完成${if (name.isBlank()) "" else " $name"}"
                }
            }
            b.tvTool.setTextColor(if (failed) dangerText else secondaryText)
            (b.tvTool.background.mutate() as GradientDrawable).setColor(if (failed) {
                ColorUtils.withAlpha(dangerText, 0.10f)
            } else {
                ColorUtils.blendColors(bgColor, context.primaryTextColor, 0.05f)
            })
            b.tvTool.setOnClickListener { callBack.onMsgCopy(m) }
        }

        private fun resolveTag(m: AiAgentMsg): TagStyle? {
            return when (m.kind) {
                AiAgentMsg.KIND_CONTEXT_INJECT -> TagStyle(
                    context.getString(R.string.ai_agent_tag_inject),
                    R.drawable.ic_chapter_list, accent,
                    ColorUtils.withAlpha(accent, 0.12f)
                )
                AiAgentMsg.KIND_BREAKDOWN_TASK -> TagStyle(
                    context.getString(R.string.ai_agent_conv_kind_breakdown),
                    R.drawable.ic_book_has, accent,
                    ColorUtils.withAlpha(accent, 0.12f)
                )
                AiAgentMsg.KIND_ERROR -> TagStyle(
                    context.getString(R.string.ai_agent_tag_error),
                    0, dangerText,
                    ColorUtils.withAlpha(dangerText, 0.10f)
                )
                AiAgentMsg.KIND_BREAKDOWN_RESULT -> when (m.status) {
                    AiAgentMsg.STATUS_STREAMING -> TagStyle(
                        context.getString(R.string.ai_agent_status_streaming),
                        0, accent, ColorUtils.withAlpha(accent, 0.10f)
                    )
                    AiAgentMsg.STATUS_PARSING -> TagStyle(
                        context.getString(R.string.ai_agent_status_parsing),
                        0, accent, ColorUtils.withAlpha(accent, 0.10f)
                    )
                    AiAgentMsg.STATUS_CANCELLED -> TagStyle(
                        context.getString(R.string.ai_agent_status_stopped),
                        R.drawable.ic_stop_black_24dp, secondaryText,
                        ColorUtils.withAlpha(secondaryText, 0.10f)
                    )
                    AiAgentMsg.STATUS_DONE -> if (m.written) TagStyle(
                        context.getString(R.string.ai_agent_status_written),
                        0, accent, ColorUtils.withAlpha(accent, 0.10f)
                    ) else TagStyle(
                        context.getString(R.string.ai_agent_status_not_written),
                        0, secondaryText, ColorUtils.withAlpha(secondaryText, 0.10f)
                    )
                    else -> null
                }
                else -> null
            }
        }

        private fun setTagIcon(tv: TextView, iconRes: Int, tint: Int) {
            val d = if (iconRes != 0) AppCompatResources.getDrawable(context, iconRes)?.mutate() else null
            if (d != null) {
                val size = 12f.dpToPx().toInt()
                d.setBounds(0, 0, size, size)
                DrawableCompat.setTint(d, tint)
            }
            tv.setCompoundDrawables(d, null, null, null)
            tv.compoundDrawablePadding = 4f.dpToPx().toInt()
        }
    }

    private class TagStyle(
        val text: String,
        val iconRes: Int,
        val textColor: Int,
        val bgColor: Int
    )

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
