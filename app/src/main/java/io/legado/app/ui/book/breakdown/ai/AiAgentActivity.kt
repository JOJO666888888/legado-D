package io.legado.app.ui.book.breakdown.ai

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentConv
import io.legado.app.data.entities.AiAgentMsg
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityAiAgentBinding
import io.legado.app.help.ai.AiAgentHelper
import io.legado.app.help.ai.AiAgentRunner
import io.legado.app.help.ai.TokenBudget
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立 AI Agent 对话页(仿 OpenAI 网页端风格)。
 *
 * 布局结构:
 *   DrawerLayout {
 *     主内容:TitleBar + 气泡 Recycler + 底部输入框(「停止生成」/「发送」)+ 浮动注入上下文按钮
 *     左侧抽屉:会话列表(新建/切换/删除)
 *   }
 */
class AiAgentActivity :
    VMBaseActivity<ActivityAiAgentBinding, AiAgentViewModel>(),
    AiAgentMsgAdapter.CallBack,
    AiAgentConvAdapter.CallBack {

    override val binding by viewBinding(ActivityAiAgentBinding::inflate)
    override val viewModel by viewModels<AiAgentViewModel>()

    private val msgAdapter = AiAgentMsgAdapter(this, this)
    private val convAdapter = AiAgentConvAdapter(this, this)
    private var currentConvId: Long = -1L

    private val pickContextLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ar ->
            val data = ar.data ?: return@registerForActivityResult
            val bookName = data.getStringExtra("bookName") ?: return@registerForActivityResult
            val author = data.getStringExtra("author").orEmpty()
            val rangeStart = data.getIntExtra("startIdx", -1)
            val rangeEnd = data.getIntExtra("endIdx", -1)
            if (rangeStart < 0 || rangeEnd < 0) return@registerForActivityResult
            injectContextFromSelection(bookName, author, rangeStart..rangeEnd)
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundColor(primaryColor)
        binding.titleBar.setTitle(R.string.ai_agent_title)
        binding.titleBar.setNavigationOnClickListener {
            if (binding.drawer.isDrawerOpen(binding.navView)) {
                binding.drawer.closeDrawer(binding.navView)
            } else {
                binding.drawer.openDrawer(binding.navView)
            }
        }
        binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        // 抽屉会话列表
        binding.navRvConvs.layoutManager = LinearLayoutManager(this)
        binding.navRvConvs.adapter = convAdapter
        binding.navFabNew.setOnClickListener { createNewConv() }
        // 气泡列表
        binding.rvMsgs.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMsgs.adapter = msgAdapter
        // 底部输入
        binding.btnSend.setOnClickListener { onSendClick() }
        binding.btnStop.setOnClickListener { onStopClick() }
        // 浮动注入上下文按钮
        binding.btnInject.setOnClickListener { openContextPicker() }
        observeConvs()
        observeEvent<String>(EventBus.AI_AGENT_MSG_UPDATED) {
            updateStopBtnState()
        }
        observeEvent<String>(EventBus.AI_AGENT_CONV_CHANGED) {
            updateStopBtnState()
        }
        val incomingConv = intent.getLongExtra("convId", -1L)
        if (incomingConv > 0) {
            switchConv(incomingConv)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val newest = appDb.aiAgentConvDao.all.firstOrNull()
                val id = if (newest != null) newest.id else createConvSync(title = null)
                withContext(Dispatchers.Main) { switchConv(id) }
            }
        }
    }

    /* ------------------------------ Options menu ------------------------------ */

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.ai_agent, menu)
        menu.apply {
            val tint = primaryTextColor
            for (i in 0 until size()) {
                getItem(i)?.icon?.setTint(tint)
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_skill_manage -> startActivity(
                Intent(this, AiSkillManageActivity::class.java)
            )
            R.id.menu_new_conv -> createNewConv()
            R.id.menu_clear_conv -> clearConvMsgs()
            R.id.menu_delete_conv -> deleteCurrentConv()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /* ------------------------------ 会话侧栏 ------------------------------ */

    private fun observeConvs() {
        appDb.aiAgentConvDao.flowAll()
            .onEach { list -> convAdapter.items = list }
            .launchIn(lifecycleScope)
    }

    private fun createNewConv() {
        lifecycleScope.launch(Dispatchers.IO) {
            val id = createConvSync(null)
            withContext(Dispatchers.Main) { switchConv(id) }
        }
    }

    private suspend fun createConvSync(title: String?): Long {
        val c = AiAgentConv(
            convKey = "chat:${System.currentTimeMillis()}:${(0..Int.MAX_VALUE).random()}",
            title = title ?: "新对话",
            kind = AiAgentConv.CHAT
        )
        return appDb.aiAgentConvDao.insert(c).firstOrNull() ?: -1L
    }

    private fun switchConv(convId: Long) {
        currentConvId = convId
        convAdapter.selectedId = convId
        observeMessages(convId)
        updateStopBtnState()
    }

    override fun onConvClick(conv: AiAgentConv) {
        switchConv(conv.id)
        binding.drawer.closeDrawers()
    }

    override fun onConvDelete(conv: AiAgentConv) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiAgentConvDao.delete(conv.id)
            if (conv.id == currentConvId) {
                val first = appDb.aiAgentConvDao.all.firstOrNull()?.id
                    ?: createConvSync(title = null)
                withContext(Dispatchers.Main) { switchConv(first) }
            }
        }
    }

    /* ------------------------------ 消息流 ------------------------------ */

    private fun observeMessages(convId: Long) {
        appDb.aiAgentMsgDao.flowByConv(convId)
            .onEach { list ->
                msgAdapter.items = list
                val size = list.size
                if (size > 0) binding.rvMsgs.smoothScrollToPosition(size - 1)
                updateStopBtnState()
            }
            .launchIn(lifecycleScope)
    }

    private fun updateStopBtnState() {
        if (currentConvId <= 0) {
            binding.btnStop.visibility = View.GONE
            binding.btnSend.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val running = AiAgentRunner.isRunning(currentConvId) ||
                appDb.aiAgentMsgDao.countPending(currentConvId) > 0
            withContext(Dispatchers.Main) {
                binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
                binding.btnSend.visibility = if (running) View.GONE else View.VISIBLE
            }
        }
    }

    /* ------------------------------ 发送/停止 ------------------------------ */

    private fun onSendClick() {
        val text = binding.tieInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (currentConvId <= 0) {
            createNewConv()
            return
        }
        binding.tieInput.text?.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            val conv = appDb.aiAgentConvDao.get(currentConvId) ?: return@launch
            val userMsg = AiAgentMsg(
                convId = conv.id,
                sortOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id),
                role = AiAgentMsg.ROLE_USER,
                content = text,
                kind = AiAgentMsg.KIND_MESSAGE
            )
            appDb.aiAgentMsgDao.insert(userMsg)
            val skill = kotlin.runCatching { AiAgentHelper.resolveBreakdownSkill(conv.skillId) }.getOrNull()
            val sys = buildConversationSystemPrompt(skill)
            val history = appDb.aiAgentMsgDao.getByConv(conv.id)
                .filter { it.kind == AiAgentMsg.KIND_MESSAGE }
                .map { it.role to it.content }
                .dropLast(1) // 最后一条即刚插入 userMsg
            AiAgentRunner.startChat(
                scope = lifecycleScope,
                conv = conv,
                systemPrompt = sys,
                historyMessages = history,
                lastUser = text
            )
            postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
            withContext(Dispatchers.Main) { updateStopBtnState() }
        }
    }

    private fun onStopClick() {
        if (currentConvId <= 0) return
        AiAgentRunner.cancel(currentConvId)
        toastOnUi("已停止生成")
    }

    private fun buildConversationSystemPrompt(skill: io.legado.app.data.entities.AiAgentSkill?): String {
        if (skill == null) return ""
        val sb = StringBuilder()
        if (skill.systemPrompt.isNotBlank()) sb.append(skill.systemPrompt).append("\n\n")
        if (skill.instruction.isNotBlank()) sb.append("方法论与操作规范:\n").append(skill.instruction).append("\n\n")
        if (skill.vocabList.isNotEmpty()) {
            sb.append("参考词汇表(使用时优先从中挑选贴切术语):\n")
            skill.vocabList.forEachIndexed { i, s -> sb.append(i + 1).append(". ").append(s).append('\n') }
            sb.append('\n')
        }
        if (skill.toolDescriptions.isNotBlank()) sb.append("技能/工具描述:\n").append(skill.toolDescriptions).append("\n\n")
        return sb.toString()
    }

    /* ------------------------------ 上下文注入 ------------------------------ */

    private fun openContextPicker() {
        val intent = Intent(this, AiContextPickerActivity::class.java)
        pickContextLauncher.launch(intent)
    }

    private fun injectContextFromSelection(bookName: String, author: String, range: IntRange) {
        if (currentConvId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(bookName, author) ?: run {
                withContext(Dispatchers.Main) { toastOnUi("书架中找不到《$bookName》") }
                return@launch
            }
            val chapters: List<BookChapter> = appDb.bookChapterDao.getChapterList(book.bookUrl)
                .filter { it.index in range }
            val (text, effective) = TokenBudget.buildChapterContext(
                book = book as Book,
                chapters = chapters,
                model = io.legado.app.help.config.AppConfig.aiModel
            )
            if (text.isBlank() || effective.isEmpty()) {
                withContext(Dispatchers.Main) { toastOnUi("所选章节内容为空或未缓存") }
                return@launch
            }
            val warn = if (effective.last < range.last) {
                ";超出预算,已截断到第 ${effective.last + 1} 章"
            } else ""
            val summary = "《${book.name}》 第 ${effective.first + 1}~${effective.last + 1} 章 已注入$warn"
            val injectMsg = AiAgentMsg(
                convId = currentConvId,
                sortOrder = appDb.aiAgentMsgDao.nextSortOrder(currentConvId),
                role = AiAgentMsg.ROLE_SYSTEM,
                content = text,
                kind = AiAgentMsg.KIND_CONTEXT_INJECT,
                contextSummary = summary
            )
            appDb.aiAgentMsgDao.insert(injectMsg)
            postEvent(EventBus.AI_AGENT_MSG_UPDATED, currentConvId.toString())
            withContext(Dispatchers.Main) {
                toastOnUi(summary)
            }
        }
    }

    /* ------------------------------ MsgAdapter CallBack ------------------------------ */

    override fun onMsgDelete(msg: AiAgentMsg) {
        lifecycleScope.launch(Dispatchers.IO) { appDb.aiAgentMsgDao.delete(msg.id) }
    }

    override fun onMsgCopy(msg: AiAgentMsg) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-agent", msg.content))
        toastOnUi("已复制到剪贴板")
    }

    private fun clearConvMsgs() {
        if (currentConvId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiAgentMsgDao.deleteByConv(currentConvId)
        }
    }

    private fun deleteCurrentConv() {
        if (currentConvId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.aiAgentConvDao.delete(currentConvId)
            val first = appDb.aiAgentConvDao.all.firstOrNull()?.id
                ?: createConvSync(title = null)
            withContext(Dispatchers.Main) { switchConv(first) }
        }
    }
}
