package io.legado.app.ui.book.breakdown.ai

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemStChatBinding
import io.legado.app.help.ai.StGatewayClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ST 网关存档多选列表 Adapter(同步页用)。
 */
class StChatPickAdapter(private val callBack: CallBack) :
    RecyclerView.Adapter<StChatPickAdapter.Holder>() {

    private val items = mutableListOf<StGatewayClient.StChatInfo>()
    private val selected = mutableSetOf<String>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    interface CallBack {
        fun onItemClicked()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(list: List<StGatewayClient.StChatInfo>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        notifyDataSetChanged()
    }

    fun getSelected(): List<StGatewayClient.StChatInfo> =
        items.filter { selected.contains(it.name) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemStChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val info = items[position]
        holder.bind(info)
    }

    inner class Holder(private val binding: ItemStChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(info: StGatewayClient.StChatInfo) {
            binding.tvName.text = info.name.removeSuffix(".jsonl")
            val sub = buildString {
                append(if (info.charName.isBlank()) "未知角色" else info.charName)
                append(" · ")
                append(info.messages)
                append(" 条")
                append(" · ")
                append(dateFormat.format(Date(info.mtime)))
            }
            binding.tvSub.text = sub
            binding.cbSelect.isChecked = selected.contains(info.name)
            binding.root.setOnClickListener {
                if (selected.contains(info.name)) {
                    selected.remove(info.name)
                } else {
                    selected.add(info.name)
                }
                binding.cbSelect.isChecked = selected.contains(info.name)
                callBack.onItemClicked()
            }
        }
    }
}
