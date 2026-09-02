package io.legado.app.ui.book.breakdown.ai

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiAgentHostBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * AI Agent 辅助页(独立入口壳)。
 *
 * 会话主页已抽为 [AiAgentFragment]:主界面底部 Tab 与本 Activity 共用同一 Fragment,
 * 保证「Tab 内/独立页」两条路径看到同一份会话状态,互不重建。
 * 本壳仅在 Tab 被隐藏(AppConfig.showAiAgentTab=false)或外部单点跳转时使用,
 * 支持透传 convId 定位到拆书任务会话。
 */
class AiAgentActivity : BaseActivity<ActivityAiAgentHostBinding>() {

    override val binding by viewBinding(ActivityAiAgentHostBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            val convId = intent.getLongExtra("convId", -1L)
            val fragment = AiAgentFragment().apply {
                if (convId > 0) {
                    arguments = (arguments ?: Bundle()).apply { putLong("convId", convId) }
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }
}