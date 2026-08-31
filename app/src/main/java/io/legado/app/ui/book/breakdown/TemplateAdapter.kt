package io.legado.app.ui.book.breakdown

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.BreakdownTemplate
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.utils.dpToPx

/**
 * 拆书模板列表
 */
class TemplateAdapter(context: Context) :
    RecyclerView.Adapter<TemplateAdapter.MyViewHolder>() {

    private val layoutInflater = LayoutInflater.from(context)
    var items: List<BreakdownTemplate> = emptyList()
        private set

    var callBack: CallBack? = null

    fun setItems(newItems: List<BreakdownTemplate>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder =
        MyViewHolder(layoutInflater.inflate(R.layout.item_template_card, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) = holder.up(items[position])

    inner class MyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvBuiltin: TextView = view.findViewById(R.id.tv_builtin)
        private val tvHint: TextView = view.findViewById(R.id.tv_hint)
        private val tvAction: TextView = view.findViewById(R.id.tv_action)

        fun up(t: BreakdownTemplate) {
            tvName.text = t.name
            tvBuiltin.visibility = if (t.isBuiltin) View.VISIBLE else View.GONE
            tvAction.text = getString(R.string.breakdown_open)
            tvHint.text = buildHint(t)
            itemView.setOnClickListener { callBack?.onItemClick(t) }
            itemView.setOnLongClickListener { callBack?.onItemLongClick(t); true }
            // 操作按钮独立点击=打开编辑
            tvAction.setOnClickListener { callBack?.onItemClick(t) }
        }

        private fun buildHint(t: BreakdownTemplate): String {
            val count = t.segmentLabels.size
            val extra = if (t.aiPromptExtra.isNotBlank()) " · 方法论附加提示已内置" else ""
            return "$count 个标签$extra"
        }

        private fun getString(id: Int): String = itemView.context.getString(id)
    }

    interface CallBack {
        fun onItemClick(template: BreakdownTemplate)
        fun onItemLongClick(template: BreakdownTemplate)
    }
}