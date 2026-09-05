package io.legado.app.ui.book.breakdown.ai

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityStGatewayBinding
import io.legado.app.help.ai.StGatewayClient
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.localBook.StChatFile
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ST 网关同步页: 配置 st-gateway-lite 地址/Token → 拉取存档列表 → 多选同步进书架。
 * 同步走 [StChatFile.importFromGateway](与本地 SAF 导入完全同一链路,含进度保护);
 * 角色卡封面由网关 PNG 原图提供,同步时一并落盘。
 */
class StGatewayActivity : VMBaseActivity<ActivityStGatewayBinding, ViewModel>(),
    StChatPickAdapter.CallBack {

    override val binding by viewBinding(ActivityStGatewayBinding::inflate)
    override val viewModel by viewModels<ViewModel>()

    private val adapter by lazy { StChatPickAdapter(this) }
    private var syncJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.st_gateway_title)
        binding.editUrl.setText(AppConfig.stGatewayUrl)
        binding.editToken.setText(AppConfig.stGatewayToken)
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSync.setOnClickListener { syncSelected() }
        // 已配置过则自动拉取列表
        if (AppConfig.stGatewayUrl.isNotBlank()) {
            loadChats()
        }
    }

    override fun onPause() {
        super.onPause()
        AppConfig.stGatewayUrl = binding.editUrl.text?.toString()?.trim().orEmpty()
        AppConfig.stGatewayToken = binding.editToken.text?.toString()?.trim().orEmpty()
    }

    override fun onDestroy() {
        syncJob?.cancel()
        super.onDestroy()
    }

    private fun testConnection() {
        AppConfig.stGatewayUrl = binding.editUrl.text?.toString()?.trim().orEmpty()
        AppConfig.stGatewayToken = binding.editToken.text?.toString()?.trim().orEmpty()
        if (AppConfig.stGatewayUrl.isBlank()) {
            toastOnUi(R.string.st_gateway_url_empty)
            return
        }
        toastOnUi(R.string.st_gateway_testing)
        lifecycleScope.launch(Dispatchers.IO) {
            val (ok, msg) = StGatewayClient.healthCheck(
                AppConfig.stGatewayUrl, AppConfig.stGatewayToken
            )
            withContext(Dispatchers.Main) {
                alert(title = getString(R.string.st_gateway_title), message = msg) { }
                if (ok) loadChats()
            }
        }
    }

    private fun loadChats() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StGatewayClient.listChats() }.onSuccess { list ->
                withContext(Dispatchers.Main) {
                    adapter.setItems(list)
                    binding.tvEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    toastOnUi(getString(R.string.st_gateway_load_fail, it.message ?: ""))
                }
            }
        }
    }

    private fun syncSelected() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) {
            toastOnUi(R.string.st_gateway_pick_empty)
            return
        }
        if (syncJob?.isActive == true) return
        toastOnUi(R.string.st_gateway_syncing)
        binding.btnSync.isEnabled = false
        syncJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // 拉取存档原文
                val files = selected.mapNotNull { info ->
                    runCatching { info.name to StGatewayClient.fetchChat(info.name) }
                        .onFailure {
                            AppLog.put("下载存档失败 ${info.name}: ${it.message}")
                        }
                        .getOrNull()
                }
                // 拉取涉及角色的 PNG 封面(无则跳过)
                val covers = mutableMapOf<String, ByteArray>()
                selected.map { it.charName }.filter { it.isNotBlank() }.distinct().forEach { char ->
                    StGatewayClient.fetchCardCover(char)?.let { png -> covers[char] = png }
                }
                StChatFile.importFromGateway(files, covers)
            }.onSuccess { result ->
                withContext(Dispatchers.Main) {
                    binding.btnSync.isEnabled = true
                    if (result.books.isNotEmpty()) {
                        toastOnUi(getString(R.string.ai_st_chat_import_ok, result.books.size))
                        finish()
                    } else {
                        toastOnUi(R.string.ai_st_chat_import_fail)
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    binding.btnSync.isEnabled = true
                    toastOnUi(getString(R.string.st_gateway_load_fail, it.message ?: ""))
                }
            }
        }
    }

    override fun onItemClicked() {
        // 更新底部同步按钮状态提示
        val selected = adapter.getSelected()
        binding.btnSync.text = if (selected.isEmpty()) {
            getString(R.string.st_gateway_sync)
        } else {
            getString(R.string.st_gateway_sync_count, selected.size)
        }
    }
}
