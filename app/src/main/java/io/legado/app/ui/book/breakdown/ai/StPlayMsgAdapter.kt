package io.legado.app.ui.book.breakdown.ai

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemStPlayMsgBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.backgroundColor

/**
 * ST 剧场消息气泡 Adapter(游玩页)。
 * 用户消息右对齐主题色背景;角色/命令回复左对齐表面色背景。
 */
class StPlayMsgAdapter : RecyclerView.Adapter<StPlayMsgAdapter.Holder>() {

    class Msg(
        val name: String,
        val isUser: Boolean,
        var text: String
    )

    private val msgs = mutableListOf<Msg>()

    @SuppressLint("NotifyDataSetChanged")
    fun addMsg(msg: Msg) {
        msgs.add(msg)
        notifyItemInserted(msgs.size - 1)
    }

    fun notifyChanged(msg: Msg) {
        val index = msgs.indexOf(msg)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemStPlayMsgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount(): Int = msgs.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(msgs[position])
    }

    inner class Holder(private val binding: ItemStPlayMsgBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: Msg) {
            val ctx = binding.root.context
            if (msg.isUser) {
                (binding.layoutBubble.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                    Gravity.END
                binding.layoutBubble.setBackgroundColor(ctx.primaryColor)
                binding.tvContent.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                binding.tvName.setTextColor(
                    ColorUtils.blendColors(
                        ctx.primaryColor, android.graphics.Color.WHITE, 0.7f
                    )
                )
                binding.tvName.text = ""
            } else {
                (binding.layoutBubble.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                    Gravity.START
                val bg = ctx.backgroundColor
                binding.layoutBubble.setBackgroundColor(
                    ColorUtils.blendColors(bg, ctx.primaryTextColor, 0.08f)
                )
                binding.tvContent.setTextColor(ctx.primaryTextColor)
                binding.tvName.setTextColor(ContextCompat.getColor(ctx, R.color.secondaryText))
                binding.tvName.text = msg.name
            }
            binding.tvContent.text = msg.text
        }
    }
}
