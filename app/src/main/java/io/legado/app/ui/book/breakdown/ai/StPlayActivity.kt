package io.legado.app.ui.book.breakdown.ai

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityStPlayBinding
import io.legado.app.help.ai.StGatewayClient
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ST 剧场(游玩页): App 内直接与 st-gateway-lite 对话,不依赖 ST 网页端。
 *
 * 会话状态(角色卡/预设/世界书/存档)全部由网关持有,App 是哑终端:
 *   - 消息以 / 开头即命令(/char /preset /world /load /new /profile /help),网关同步返回;
 *   - 普通消息走 SSE 流式对话,回复实时追加到气泡;
 *   - 进入页面拉取会话快照(最近历史+开场白)回显;
 *   - 游玩产生的存档在网关侧落盘(.jsonl),可随时在同步页拉回书架阅读。
 */
class StPlayActivity : VMBaseActivity<ActivityStPlayBinding, ViewModel>() {

    override val binding by viewBinding(ActivityStPlayBinding::inflate)
    override val viewModel by viewModels<ViewModel>()

    private val adapter by lazy { StPlayMsgAdapter() }
    private var streamJob: Job? = null
    private var streaming = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.st_play_title)
        if (AppConfig.stGatewayUrl.isBlank()) {
            toastOnUi(R.string.st_gateway_url_empty)
            finish()
            return
        }
        binding.rvMsgs.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMsgs.adapter = adapter
        binding.btnSend.setOnClickListener { onSend() }
        binding.btnStop.setOnClickListener { streamJob?.cancel(); updateStopBtn(false) }
        binding.btnHelp.setOnClickListener { send("/help") }
        loadSession()
    }

    override fun onDestroy() {
        streamJob?.cancel()
        super.onDestroy()
    }

    private fun loadSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StGatewayClient.getSession(SESSION_ID) }.onSuccess { session ->
                withContext(Dispatchers.Main) {
                    binding.titleBar.title = buildString {
                        append(getString(R.string.st_play_title))
                        if (session.character.isNotBlank()) {
                            append(" · ")
                            append(session.character)
                        }
                    }
                    if (!session.llmConfigured) {
                        toastOnUi(R.string.st_play_llm_unconfigured)
                    }
                    if (session.history.isEmpty()) {
                        // 新会话: 显示开场白(若有)
                        session.greeting?.takeIf { it.isNotBlank() }?.let { greeting ->
                            adapter.addMsg(StPlayMsgAdapter.Msg(
                                name = session.character.ifBlank { "ST" },
                                isUser = false, text = greeting
                            ))
                        }
                    } else {
                        session.history.forEach {
                            adapter.addMsg(
                                StPlayMsgAdapter.Msg(
                                    name = it.name,
                                    isUser = it.isUser,
                                    text = it.mes
                                )
                            )
                        }
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    toastOnUi(getString(R.string.st_gateway_load_fail, it.message ?: ""))
                }
            }
        }
    }

    private fun onSend() {
        val text = binding.tieInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (streaming) return
        binding.tieInput.setText("")
        send(text)
    }

    private fun send(text: String) {
        if (streaming) return
        adapter.addMsg(StPlayMsgAdapter.Msg(name = "", isUser = true, text = text))
        // 占位 assistant 气泡,流式增量追加到这里
        val bubble = StPlayMsgAdapter.Msg(name = "…", isUser = false, text = "")
        adapter.addMsg(bubble)
        streaming = true
        updateStopBtn(true)
        streamJob = lifecycleScope.launch {
            StGatewayClient.streamChat(SESSION_ID, text).collect { ev ->
                when (ev) {
                    is StGatewayClient.PlayEvent.Delta -> {
                        bubble.text += ev.text
                        adapter.notifyChanged(bubble)
                    }

                    is StGatewayClient.PlayEvent.Reasoning -> Unit // 游玩页不展示思维链

                    is StGatewayClient.PlayEvent.Done -> {
                        if (bubble.text != ev.text) {
                            bubble.text = ev.text
                            adapter.notifyChanged(bubble)
                        }
                        finishTurn()
                    }

                    is StGatewayClient.PlayEvent.Error -> {
                        bubble.text = bubble.text.ifEmpty {
                            getString(R.string.st_play_error, ev.message)
                        }
                        adapter.notifyChanged(bubble)
                        finishTurn()
                    }
                }
            }
        }
        streamJob?.invokeOnCompletion {
            // Flow 被取消(停止按钮): 结束流式状态
            runOnUiThread { finishTurn() }
        }
    }

    private fun finishTurn() {
        streaming = false
        updateStopBtn(false)
    }

    private fun updateStopBtn(show: Boolean) {
        runOnUiThread {
            binding.btnStop.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnSend.visibility = if (show) View.GONE else View.VISIBLE
        }
    }

    companion object {
        /** App 游玩固定会话 id:网关 profile 维度,用 /char 切换角色 */
        const val SESSION_ID = "legado:play"
    }
}
