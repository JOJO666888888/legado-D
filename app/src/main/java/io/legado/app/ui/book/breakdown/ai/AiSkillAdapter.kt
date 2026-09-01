package io.legado.app.ui.book.breakdown.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.AiAgentSkill
import io.legado.app.databinding.ItemAiSkillBinding

class AiSkillAdapter(
    context: Context,
    private val callBack: CallBack
) : ListAdapter<AiAgentSkill, AiSkillAdapter.VH>(DIFF) {

    var items: List<AiAgentSkill> = emptyList()
        set(value) {
            field = value
            submitList(value)
        }

    private val inflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAiSkillBinding.inflate(inflater, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class VH(private val b: ItemAiSkillBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: AiAgentSkill) = with(b) {
            tvName.text = s.name
            tvCategory.text = when (s.category) {
                AiAgentSkill.BREAKDOWN -> "拆书"
                AiAgentSkill.AGENT_PERSONA -> "Agent人设"
                AiAgentSkill.TOOL_SKILL -> "工具"
                AiAgentSkill.METHODOLOGY -> "方法论"
                else -> "自定义"
            }
            tvVocab.text = "词汇表:${s.vocabList.size} 条"
            swEnabled.isChecked = s.enabled
            ivBuiltinBadge.visibility = if (s.readOnly) android.view.View.VISIBLE else android.view.View.GONE
            root.setOnClickListener { callBack.onClick(s) }
            btnDuplicate.setOnClickListener { callBack.onDuplicate(s) }
            swEnabled.setOnCheckedChangeListener(null)
            swEnabled.setOnClickListener { callBack.onToggle(s) }
            btnDelete.setOnClickListener { callBack.onDelete(s) }
            Unit
        }
    }

    interface CallBack {
        fun onClick(skill: AiAgentSkill)
        fun onDuplicate(skill: AiAgentSkill)
        fun onToggle(skill: AiAgentSkill)
        fun onDelete(skill: AiAgentSkill)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AiAgentSkill>() {
            override fun areItemsTheSame(a: AiAgentSkill, b: AiAgentSkill) = a.id == b.id
            override fun areContentsTheSame(a: AiAgentSkill, b: AiAgentSkill) =
                a == b
        }
    }
}
