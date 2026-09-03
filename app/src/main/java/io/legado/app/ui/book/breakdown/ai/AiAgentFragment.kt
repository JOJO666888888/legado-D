package io.legado.app.ui.book.breakdown.ai

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
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
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.breakdown.AiConfigActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.share
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI Agent 会话主页(主界面顶级 Tab 承载页)。
 *
 * 与旧 AiAgentActivity 共用同一套布局与数据层;Activity 侧已改为宿主壳。
 * 布局结构:
 *   DrawerLayout {
 *     主内容:TitleBar + 气泡 Recycler + 底部输入框(「停止生成」/「发送」)+ 浮动注入上下文按钮
 *     左侧抽屉:会话列表(新建/切换/删除)
 *   }
 */
class AiAgentFragment() : VMBaseFragment<AiAgentViewModel>(R.layout.activity_ai_agent),
    MainFragmentInterface,
    AiAgentMsgAdapter.CallBack,
    AiAgentConvAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<AiAgentViewModel>()

    private val binding by viewBinding(ActivityAiAgentBinding::bind)
    private val msgAdapter by lazy { AiAgentMsgAdapter(requireContext(), this) }
    private val convAdapter by lazy { AiAgentConvAdapter(requireContext(), this) }
    private var currentConvId: Long = -1L
    private var pendingOpenConvId: Long = -1L

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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundColor(primaryColor)
        binding.titleBar.setTitle(R.string.ai_agent_title)
        applyViewStyles()
        binding.titleBar.setNavigationOnClickListener {
            if (binding.drawer.isDrawerOpen(binding.navView)) {
                binding.drawer.closeDrawer(binding.navView)
            } else {
                binding.drawer.openDrawer(binding.navView)
            }
        }
        binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        // 抽屉会话列表
        binding.navRvConvs.layoutManager = LinearLayoutManager(requireContext())
        binding.navRvConvs.adapter = convAdapter
        binding.navFabNew.setOnClickListener { createNewConv() }
        // 抽屉「设置」:收纳 AI 设置 + Skill/Agent 管理
        binding.navSettings.setOnClickListener { showSettingsDialog() }
        // 气泡列表
        binding.rvMsgs.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMsgs.adapter = msgAdapter
        // 底部输入
        binding.btnSend.setOnClickListener { onSendClick() }
        binding.btnStop.setOnClickListener { onStopClick() }
        // 注入上下文(输入栏内轻量入口)
        binding.btnInject.setOnClickListener { openContextPicker() }
        // 空状态快捷入口
        binding.btnEmptyNew.setOnClickListener { createNewConv() }
        binding.btnEmptySkill.setOnClickListener { startActivity<AiSkillManageActivity>() }
        observeConvs()
        observeEvent<String>(EventBus.AI_AGENT_MSG_UPDATED) {
            updateStopBtnState()
        }
        observeEvent<String>(EventBus.AI_AGENT_CONV_CHANGED) {
            updateStopBtnState()
        }
        // 优先处理外部定位(拆书联动/宿主透传),否则取最新会话
        val incomingConv = pendingOpenConvId.takeIf { it > 0 }
            ?: arguments?.getLong("convId", -1L) ?: -1L
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

    /* ------------------------------ 设置入口 ------------------------------ */

    /**
     * 抽屉「设置」按钮:弹出列表收纳 AI 设置与 Skill/Agent 管理。
     * 两个入口均跳转到真实可操作的 Activity,非占位按钮。
     */
    private fun showSettingsDialog() {
        val ctx = requireContext()
        val labels = arrayOf(
            getString(R.string.ai_agent_settings_ai_config),
            getString(R.string.ai_agent_settings_skill_manage)
        )
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_agent_settings)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> startActivity<AiConfigActivity>()
                    1 -> startActivity<AiSkillManageActivity>()
                }
            }
            .show()
    }

    /* ------------------------------ 视觉样式(主题派生) ------------------------------ */

    /**
     * 依据 ThemeStore 动态配色为输入卡片/按钮/标签着色,自动适配深浅模式。
     * 不引入新依赖,仅用 Context 主题色 + 现有 drawable 形状。
     */
    private fun applyViewStyles() {
        val ctx = requireContext()
        val bg = ctx.backgroundColor
        val surface = ColorUtils.blendColors(bg, ctx.primaryTextColor, 0.06f)
        val subtle = ColorUtils.blendColors(bg, ctx.primaryTextColor, 0.08f)

        // 底部输入卡片
        binding.layoutInputBar.setCardBackgroundColor(surface)

        // 发送按钮:主题色圆形 + 白色图标
        (binding.btnSend.background.mutate() as GradientDrawable).setColor(ctx.primaryColor)
        binding.btnSend.imageTintList = ColorStateList.valueOf(Color.WHITE)

        // 停止按钮:浅色圆形 + 主题色图标(生成中状态下与发送按钮互斥显示)
        (binding.btnStop.background.mutate() as GradientDrawable).setColor(
            ColorUtils.blendColors(bg, ctx.primaryTextColor, 0.10f)
        )
        binding.btnStop.imageTintList = ColorStateList.valueOf(ctx.primaryTextColor)

        // 注入上下文轻量 chip(文字固定黑色,确保浅色主题下可读)
        binding.btnInject.setTextColor(Color.BLACK)
        (binding.btnInject.background.mutate() as GradientDrawable).setColor(subtle)
        setStartIcon(binding.btnInject, R.drawable.ic_chapter_list, ctx.secondaryTextColor, 14)

        // 抽屉「新建对话」主按钮(文字固定黑色,确保浅色主题下可读)
        binding.navFabNew.setTextColor(Color.BLACK)
        (binding.navFabNew.background.mutate() as GradientDrawable).setColor(ctx.primaryColor)
        setStartIcon(binding.navFabNew, R.drawable.ic_add, ctx.primaryTextColor, 18)

        // 抽屉「设置」按钮(收纳 AI 设置 + Skill/Agent 管理入口)
        binding.navSettings.setTextColor(Color.BLACK)
        (binding.navSettings.background.mutate() as GradientDrawable).setColor(subtle)
        setStartIcon(binding.navSettings, R.drawable.ic_settings, ctx.primaryTextColor, 18)

        // 空状态快捷入口
        binding.btnEmptyNew.setTextColor(Color.WHITE)
        (binding.btnEmptyNew.background.mutate() as GradientDrawable).setColor(ctx.primaryColor)
        setStartIcon(binding.btnEmptyNew, R.drawable.ic_add, Color.WHITE, 18)

        binding.btnEmptySkill.setTextColor(ctx.primaryColor)
        (binding.btnEmptySkill.background.mutate() as GradientDrawable).setColor(subtle)
        setStartIcon(binding.btnEmptySkill, R.drawable.ic_code, ctx.primaryColor, 16)
    }

    /** 设置 TextView 起始图标的大小与着色(基于 compoundDrawable) */
    private fun setStartIcon(tv: TextView, iconRes: Int, tint: Int, sizeDp: Int) {
        val d = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(requireContext(), iconRes)?.mutate() ?: return
        val size = sizeDp.dpToPx()
        d.setBounds(0, 0, size, size)
        DrawableCompat.setTint(d, tint)
        tv.setCompoundDrawables(d, null, null, null)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.ai_agent, menu)
        menu.apply {
            val tint = primaryTextColor
            for (i in 0 until size()) {
                getItem(i)?.icon?.setTint(tint)
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_skill_manage -> startActivity<AiSkillManageActivity>()
            R.id.menu_new_conv -> createNewConv()
            R.id.menu_export_conv -> exportCurrentConv()
            R.id.menu_clear_conv -> clearConvMsgs()
            R.id.menu_delete_conv -> deleteCurrentConv()
        }
    }

    /* ------------------------------ 外部定位入口 ------------------------------ */

    /** 拆书联动/宿主调用:定位到指定会话展示进度 */
    fun openConv(convId: Long) {
        if (convId <= 0) return
        if (view == null) {
            pendingOpenConvId = convId
            return
        }
        switchConv(convId)
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
        alert(R.string.delete) {
            setMessage(getString(R.string.ai_agent_delete_conv_confirm))
            okButton { doDeleteConv(conv) }
            cancelButton()
        }.show()
    }

    private fun doDeleteConv(conv: AiAgentConv) {
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
                binding.layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
            // 历史由 AiAgentRunner 内部从 DB 重建(窗口起点对齐 user),此处仅提交最新 user 提问
            AiAgentRunner.startChat(
                scope = lifecycleScope,
                conv = conv,
                systemPrompt = sys,
                historyMessages = emptyList(),
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
        val intent = Intent(requireContext(), AiContextPickerActivity::class.java)
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
            // 静默后台建索引(M9,best-effort;未配 EMBEDDING 或已是最新则跳过,失败仅 toast 不阻塞)
            maybeBackgroundIndex(book as Book)
            withContext(Dispatchers.Main) {
                toastOnUi(summary)
            }
        }
    }

    /** 首次注入后后台静默建全书语义索引(指纹一致/进行中/未配置时跳过) */
    private fun maybeBackgroundIndex(book: Book) {
        val bookId = io.legado.app.help.ai.EmbeddingPipeline.embeddingBookId(book.bookUrl)
        val chapterCount = appDb.bookChapterDao.getChapterList(book.bookUrl).size
        if (io.legado.app.help.ai.EmbeddingPipeline.isFresh(book, chapterCount)) return
        val st = io.legado.app.data.vector.VectorRepository.status(bookId)
        if (st.state == io.legado.app.data.vector.VectorRepository.State.INDEXING) return
        if (st.state == io.legado.app.data.vector.VectorRepository.State.BLOCKED) return
        val conf = io.legado.app.help.ai.AiModelResolver.resolve(io.legado.app.help.ai.AiModelResolver.Role.EMBEDDING)
        if (conf.baseUrl.isBlank() || conf.model.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val err = io.legado.app.help.ai.EmbeddingPipeline.embedBook(book) { _, _ -> }
            if (err != null) {
                withContext(Dispatchers.Main) { toastOnUi(err.take(80)) }
            }
        }
    }

    /* ------------------------------ MsgAdapter CallBack ------------------------------ */

    override fun onMsgDelete(msg: AiAgentMsg) {
        lifecycleScope.launch(Dispatchers.IO) { appDb.aiAgentMsgDao.delete(msg.id) }
    }

    override fun onMsgCopy(msg: AiAgentMsg) {
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
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

    /* ------------------------------ 会话导出(M11) ------------------------------ */

    /** 导出当前会话为 Markdown 并分享(cache 文件 + FileProvider) */
    private fun exportCurrentConv() {
        if (currentConvId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val conv = appDb.aiAgentConvDao.get(currentConvId) ?: return@launch
            val msgs = appDb.aiAgentMsgDao.getByConv(currentConvId)
            val md = buildConversationMarkdown(conv, msgs)
            val file = java.io.File(
                requireContext().cacheDir,
                "ai-conv-${conv.id}-${System.currentTimeMillis()}.md"
            )
            kotlin.runCatching { file.writeText(md) }.onFailure {
                withContext(Dispatchers.Main) { toastOnUi(it.message ?: "导出失败") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.ai_agent_export_ok)
                requireContext().share(file, "text/markdown")
            }
        }
    }

    private fun buildConversationMarkdown(
        conv: AiAgentConv,
        msgs: List<AiAgentMsg>
    ): String {
        val sb = StringBuilder()
        sb.append("# 会话:").append(conv.title.ifBlank { conv.id.toString() }).append("\n\n")
        sb.append("- 分类:").append(conv.kind).append('\n')
        if (conv.rollingSummary.isNotBlank()) {
            sb.append("- 前情提要:").append(conv.rollingSummary.replace('\n', ' ')).append('\n')
        }
        sb.append("- 导出时间:").append(java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date())).append("\n\n---\n\n")
        msgs.forEach { m ->
            when (m.role) {
                AiAgentMsg.ROLE_USER -> {
                    sb.append("## 用户\n\n").append(m.content).append("\n\n")
                }
                AiAgentMsg.ROLE_TOOL -> {
                    sb.append("> **工具**").append(if (m.toolCallId.isBlank()) "" else " (`${m.toolCallId}`)")
                        .append(":\n\n").append(m.content).append("\n\n")
                }
                AiAgentMsg.ROLE_SYSTEM -> {
                    if (m.kind == AiAgentMsg.KIND_CONTEXT_INJECT && m.contextSummary.isNotBlank()) {
                        sb.append("> **上下文注入:** ").append(m.contextSummary).append("\n\n")
                    } else if (m.kind == AiAgentMsg.KIND_ERROR) {
                        sb.append("> **错误:** ").append(m.content).append("\n\n")
                    }
                }
                else -> {
                    sb.append("## AI\n\n")
                    if (m.thinking.isNotBlank()) {
                        sb.append("<details><summary>思考过程</summary>\n\n")
                        sb.append(m.thinking).append("\n\n</details>\n\n")
                    }
                    if (m.toolCallsJson != "[]" && m.toolCallsJson.isNotBlank()) {
                        sb.append("> 工具调用:`").append(m.toolCallsJson).append("`\n\n")
                    }
                    sb.append(m.content).append("\n\n")
                }
            }
        }
        return sb.toString()
    }
}