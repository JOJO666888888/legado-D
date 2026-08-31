package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.Menu
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.ActivityAiConfigBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 拆书设置页骨架:服务地址/API Key/模型/测试连通
 * API Key 只存 SharedPreferences,不进备份(符合方案要求)
 */
class AiConfigActivity : VMBaseActivity<ActivityAiConfigBinding, AiConfigViewModel>() {

    override val binding by viewBinding(ActivityAiConfigBinding::inflate)
    override val viewModel by viewModels<AiConfigViewModel>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.breakdown_ai_title)
        // 加载现有配置
        binding.editBaseUrl.setText(AppConfig.aiBaseUrl)
        binding.editApiKey.setText(AppConfig.aiApiKey)
        binding.editModel.setText(AppConfig.aiModel)
        binding.editMaxSend.setText(AppConfig.aiMaxSendChars.toString())
        // 绑定测试按钮
        binding.tvTest.setOnClickListener { testConnection() }
    }

    override fun onPause() {
        super.onPause()
        // 保存配置到 SharedPreferences
        val baseUrl = binding.editBaseUrl.text?.toString()?.trim().orEmpty()
        val apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        val model = binding.editModel.text?.toString()?.trim().orEmpty()
        val maxSend = binding.editMaxSend.text?.toString()?.trim()?.toIntOrNull() ?: 30000
        AppConfig.aiBaseUrl = baseUrl
        AppConfig.aiApiKey = apiKey
        AppConfig.aiModel = model
        AppConfig.aiMaxSendChars = maxSend
    }

    private fun testConnection() {
        val baseUrl = binding.editBaseUrl.text?.toString()?.trim().orEmpty()
        val apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isBlank()) {
            toastOnUi(R.string.breakdown_ai_base_url)
            return
        }
        toastOnUi(R.string.breakdown_ai_testing)
        binding.tvTest.isEnabled = false
        val testUrl = if (baseUrl.endsWith("/")) {
            "${baseUrl}models"
        } else {
            "$baseUrl/models"
        }
        val request = Request.Builder()
            .url(testUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        GlobalScope.launch(IO) {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        binding.tvTest.isEnabled = true
                        toastOnUi(getString(R.string.breakdown_ai_test_fail, e.message ?: "IO error"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    runOnUiThread {
                        binding.tvTest.isEnabled = true
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val summary = if (body.length > 80) body.take(80) + "…" else body
                            toastOnUi(getString(R.string.breakdown_ai_test_ok, summary))
                        } else {
                            val msg = "${response.code} ${response.message}"
                            toastOnUi(getString(R.string.breakdown_ai_test_fail, msg))
                        }
                    }
                }
            })
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        return super.onCompatCreateOptionsMenu(menu)
    }
}