package io.legado.app.help.ai

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentConv
import io.legado.app.data.entities.AiAgentMsg
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.help.breakdown.BreakdownAiRunner
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI Agent 业务调度中心(单例)。
 *
 *  负责:
 *   - 拆书任务 → 创建/复用 AiAgentConv(去重 convKey),流式 SSE,结果 parseAndVerify 回写草稿
 *   - 普通多轮对话 → 取 conv 的 history messages 构造上下文,流式写回 ASSISTANT msg
 *   - 任务级 Cancellation → Job map + cancel(convId),中断后不写半成品(状态 CANCELLED)
 */
object AiAgentRunner {

    /** 工具轮最大循环次数(防失控) */
    const val MAX_ROUNDS = 5

    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun isRunning(convId: Long) = jobs[convId]?.isActive == true

    fun cancel(convId: Long) {
        jobs[convId]?.cancel()
        jobs.remove(convId)
    }

    /* ------------------------------ 拆书任务 ------------------------------ */

    /**
     * 从拆书房发起单章/批量 AI 拆解任务。
     *
     * 结果回写口径:
     *   - parseAndVerify 成功 → 走 BreakdownHelper.saveAiChapter(锚点校验+行号↔偏移)回写草稿
     *   - 用户在流式阶段 cancel → 已累积 msg 保留(方便排查),但回写逻辑不触发,written=false
     *   - 错误 → 写一条 KIND_ERROR banner msg + conv.lastError,不写拆书草稿
     */
    fun startBreakdownTask(
        scope: CoroutineScope,
        bd: BookBreakdown,
        chaptersToRun: List<Pair<Int, String>>,
        numberedContents: Map<Int, String>,
        skill: io.legado.app.data.entities.AiAgentSkill = AiAgentHelper.resolveBreakdownSkill(bd.skillId)
    ): Long {
        val batchId: String? = if (chaptersToRun.size > 1) "batch_${System.currentTimeMillis()}" else null
        val convKey = AiAgentConv.buildBreakdownKey(bd.id, skill.id, batchId)
        val conv = appDb.aiAgentConvDao.getByKey(convKey) ?: run {
            val titlePrefix = if (batchId == null) "拆书·单章 " else "拆书·批量 "
            val titles = chaptersToRun.joinToString("、") { "${it.first + 1}.${it.second}" }.take(60)
            val c = AiAgentConv(
                convKey = convKey,
                title = "$titlePrefix《${bd.bookName}》$titles",
                kind = AiAgentConv.TASK_BREAKDOWN,
                skillId = skill.id,
                refBreakdownId = bd.id,
                refChapterIndex = if (chaptersToRun.size == 1) chaptersToRun.first().first else -1
            )
            val id = appDb.aiAgentConvDao.insert(c).first()
            c.copy(id = id)
        }
        val taskMsg = AiAgentMsg(
            convId = conv.id,
            sortOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id),
            role = AiAgentMsg.ROLE_USER,
            content = "AI 拆解任务:共 ${chaptersToRun.size} 章,模板/skill=${skill.name}",
            kind = AiAgentMsg.KIND_BREAKDOWN_TASK,
            refBreakdownId = bd.id,
            refChapterIndex = if (chaptersToRun.size == 1) chaptersToRun.first().first else -1
        )
        appDb.aiAgentMsgDao.insert(taskMsg)
        appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_STREAMING, System.currentTimeMillis())
        postEvent(EventBus.AI_AGENT_CONV_CHANGED, conv.id.toString())

        val job = scope.launch(Dispatchers.IO) {
            var error: String? = null
            try {
                chaptersToRun.forEachIndexed { i, (idx, title) ->
                    currentCoroutineContext().ensureActive()
                    val content = numberedContents[idx] ?: return@forEachIndexed
                    streamSingleBreakdown(
                        convId = conv.id,
                        bd = bd,
                        chapterIndex = idx,
                        chapterTitle = title,
                        numberedContent = content,
                        skill = skill
                    )
                    if (i != chaptersToRun.size - 1) delay(300)
                }
            } catch (ce: CancellationException) {
                finalizeConvCancelled(conv.id)
                throw ce
            } catch (t: Throwable) {
                error = t.message ?: "AI 拆解失败"
            }
            withContext(Dispatchers.Main.immediate) {
                if (error == null) {
                    appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_IDLE, System.currentTimeMillis())
                } else {
                    appDb.aiAgentConvDao.setError(conv.id, error!!, System.currentTimeMillis())
                    val errMsg = AiAgentMsg(
                        convId = conv.id,
                        sortOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id),
                        role = AiAgentMsg.ROLE_SYSTEM,
                        content = error!!,
                        kind = AiAgentMsg.KIND_ERROR
                    )
                    appDb.aiAgentMsgDao.insert(errMsg)
                }
                postEvent(EventBus.AI_AGENT_CONV_CHANGED, conv.id.toString())
                postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
            }
        }.also {
            it.invokeOnCompletion { jobs.remove(conv.id) }
        }
        jobs[conv.id] = job
        return conv.id
    }

    /** 单章流式拆解(1 次 SSE → 解析 → 回写) */
    private suspend fun streamSingleBreakdown(
        convId: Long,
        bd: BookBreakdown,
        chapterIndex: Int,
        chapterTitle: String,
        numberedContent: String,
        skill: io.legado.app.data.entities.AiAgentSkill
    ) {
        val now = System.currentTimeMillis()
        val order = appDb.aiAgentMsgDao.nextSortOrder(convId)
        val msgId = appDb.aiAgentMsgDao.insert(
            AiAgentMsg(
                convId = convId,
                sortOrder = order,
                role = AiAgentMsg.ROLE_ASSISTANT,
                content = "",
                kind = AiAgentMsg.KIND_BREAKDOWN_RESULT,
                status = AiAgentMsg.STATUS_STREAMING,
                refBreakdownId = bd.id,
                refChapterIndex = chapterIndex,
                createTime = now,
                updateTime = now
            )
        ).firstOrNull() ?: return
        postEvent(EventBus.AI_AGENT_MSG_UPDATED, convId.toString())
        val system = BreakdownAiRunner.buildSystemPrompt(skill.vocabList, skill.instruction)
        val user = "章节《$chapterTitle》预编号文本(行号以 `N|` 开头,引用时直接复制此行内容):\n\n$numberedContent"
        val fullBuilder = StringBuilder(4_096)
        var flushAcc = 0
        val flushEach = 5
        try {
            kotlinx.coroutines.supervisorScope {
                SseStreamClient.streamChat(
                    system = system,
                    user = user,
                    baseUrl = AppConfig.aiBaseUrl,
                    apiKey = AppConfig.aiApiKey,
                    model = AppConfig.aiModel,
                    jsonMode = true,
                    temperature = 0.2
                ).collect { ev ->
                    when (ev) {
                        is SseStreamClient.StreamEvent.Reasoning -> Unit // 拆书链路忽略思考流
                        is SseStreamClient.StreamEvent.Delta -> {
                            fullBuilder.append(ev.chunk)
                            flushAcc++
                            if (flushAcc >= flushEach) {
                                flushAcc = 0
                                val t = System.currentTimeMillis()
                                appDb.aiAgentMsgDao.appendContent(msgId, ev.chunk, t)
                                postEvent(EventBus.AI_AGENT_MSG_UPDATED, convId.toString())
                            }
                        }
                        is SseStreamClient.StreamEvent.Done -> {
                            val delta = if (fullBuilder.length < ev.fullContent.length) {
                                ev.fullContent.substring(fullBuilder.length)
                            } else ""
                            if (delta.isNotEmpty()) {
                                appDb.aiAgentMsgDao.appendContent(msgId, delta, System.currentTimeMillis())
                            }
                            fullBuilder.clear().append(ev.fullContent)
                            appDb.aiAgentConvDao.setState(convId, AiAgentConv.STATE_PARSING, System.currentTimeMillis())
                            appDb.aiAgentMsgDao.finalizeContent(
                                id = msgId,
                                content = fullBuilder.toString(),
                                status = AiAgentMsg.STATUS_PARSING,
                                written = false,
                                time = System.currentTimeMillis()
                            )
                            postEvent(EventBus.AI_AGENT_MSG_UPDATED, convId.toString())
                            val result = BreakdownAiRunner.parseAndVerify(fullBuilder.toString())
                            val written = withContext(Dispatchers.IO) {
                                val err = BreakdownHelper.saveAiChapter(
                                    bdId = bd.id,
                                    chapterIndex = chapterIndex,
                                    chapterName = chapterTitle,
                                    numberedContent = numberedContent,
                                    result = result
                                )
                                err == null
                            }
                            appDb.aiAgentMsgDao.finalizeContent(
                                id = msgId,
                                content = fullBuilder.toString(),
                                status = AiAgentMsg.STATUS_DONE,
                                written = written,
                                time = System.currentTimeMillis()
                            )
                            appDb.aiAgentConvDao.setState(convId, AiAgentConv.STATE_IDLE, System.currentTimeMillis())
                        }
                        is SseStreamClient.StreamEvent.Error -> throw ev.t
                    }
                }
            }
        } catch (ce: CancellationException) {
            appDb.aiAgentMsgDao.finalizeContent(
                id = msgId,
                content = fullBuilder.toString(),
                status = AiAgentMsg.STATUS_CANCELLED,
                written = false,
                time = System.currentTimeMillis()
            )
            throw ce
        } catch (t: Throwable) {
            // SSE 层网络/上层错误:以错误消息落库并继续(避免协程崩溃)
            appDb.aiAgentMsgDao.finalizeContent(
                id = msgId,
                content = fullBuilder.toString(),
                status = AiAgentMsg.STATUS_DONE,
                written = false,
                time = System.currentTimeMillis()
            )
            val errMsg = AiAgentMsg(
                convId = convId,
                sortOrder = appDb.aiAgentMsgDao.nextSortOrder(convId) + 1,
                role = AiAgentMsg.ROLE_SYSTEM,
                content = t.message ?: "AI 请求失败",
                kind = AiAgentMsg.KIND_ERROR
            )
            appDb.aiAgentMsgDao.insert(errMsg)
            appDb.aiAgentConvDao.setError(convId, t.message ?: "AI 请求失败", System.currentTimeMillis())
            return
        } finally {
            postEvent(EventBus.AI_AGENT_MSG_UPDATED, convId.toString())
        }
    }

    private fun finalizeConvCancelled(convId: Long) {
        appDb.aiAgentConvDao.setState(convId, AiAgentConv.STATE_IDLE, System.currentTimeMillis())
    }

    /* ------------------------------ 普通对话(工具轮) ------------------------------ */

    /**
     * 发起普通多轮对话(M6 runToolLoop)。
     *
     * 与旧实现差异:
     *   - 参数 historyMessages 保留兼容,但实际从 DB 重建(窗口起点落在 user 消息,避免半截 tool 结果);
     *   - 流式一轮支持 thinking 增量(Reasoning → assistantMsg.thinking,每 5 块 flush)+ tool_calls;
     *   - Done 时若带 toolCalls → 落库 assistant(toolCallsJson=DONE)+ 执行工具写 TOOL 消息 → 续跑下一轮,
     *     MAX_ROUNDS=5 防失控;无 toolCalls 或达上限 → 收官下一条完整 assistant 消息;
     *   - 请求前 silent refresh 前情提要(M4,CHEAP 角色,失败降级)。
     *
     * @return 第一条 assistant 消息 id
     */
    fun startChat(
        scope: CoroutineScope,
        conv: AiAgentConv,
        systemPrompt: String,
        historyMessages: List<Pair<String, String>>,
        lastUser: String
    ): Long {
        val now = System.currentTimeMillis()
        val order = appDb.aiAgentMsgDao.nextSortOrder(conv.id)
        val firstAssistantId = appDb.aiAgentMsgDao.insert(
            AiAgentMsg(
                convId = conv.id,
                sortOrder = order,
                role = AiAgentMsg.ROLE_ASSISTANT,
                content = "",
                kind = AiAgentMsg.KIND_MESSAGE,
                status = AiAgentMsg.STATUS_STREAMING
            )
        ).firstOrNull() ?: return -1L
        appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_STREAMING, now)
        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
        val job = scope.launch(Dispatchers.IO) {
            try {
                runToolLoop(conv, firstAssistantId, systemPrompt, lastUser)
            } catch (_: CancellationException) {
                // 消息级取消状态已在 runToolLoop 内处理(保留已累积内容);这里只复位会话状态
                finalizeConvCancelled(conv.id)
            } catch (t: Throwable) {
                appDb.aiAgentConvDao.setError(conv.id, t.message ?: "对话失败", System.currentTimeMillis())
                appDb.aiAgentMsgDao.finalizeContent(
                    id = firstAssistantId,
                    content = "",
                    status = AiAgentMsg.STATUS_DONE,
                    written = false,
                    time = System.currentTimeMillis()
                )
                val err = AiAgentMsg(
                    convId = conv.id,
                    sortOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id),
                    role = AiAgentMsg.ROLE_SYSTEM,
                    content = t.message ?: "对话失败",
                    kind = AiAgentMsg.KIND_ERROR
                )
                appDb.aiAgentMsgDao.insert(err)
            } finally {
                appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_IDLE, System.currentTimeMillis())
                postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                postEvent(EventBus.AI_AGENT_CONV_CHANGED, conv.id.toString())
                jobs.remove(conv.id)
            }
        }
        jobs[conv.id] = job
        job.invokeOnCompletion { jobs.remove(conv.id) }
        return firstAssistantId
    }

    /** 工具轮核心(循环内每轮一个 assistant 消息;普通对话专用,拆书链路不经过这里,见 D4) */
    private suspend fun runToolLoop(
        conv: AiAgentConv,
        firstAssistantId: Long,
        systemPrompt: String,
        lastUser: String
    ) {
        val dialect = AppConfig.aiDialect
        val conf = AiModelResolver.resolve(AiModelResolver.Role.CHAT)
        // 1. 前情提要 refresh(消息数 > 20 且水位落后时才真正请求;失败静默)
        val rolledSystem = refreshRollingSummary(conv.id, systemPrompt)
        // 2. 初始历史(窗口起点落在 user 消息;最后一轮 user 已在 DB,单独作为 lastUser)
        //    claude 方言不支持 system 混在 messages 里,上下文注入块仅 openai 方言带入
        val history = buildLoopHistory(conv.id, includeSystemContext = dialect == SseStreamClient.DIALECT_OPENAI)
        val defaultBookUrl = resolveDefaultBookUrl(conv)
        var round = 0
        var assistantMsgId = firstAssistantId
        val content = StringBuilder(4_096)
        val thinking = StringBuilder(1_024)
        try {
            kotlinx.coroutines.supervisorScope {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    round++
                    content.setLength(0)
                    thinking.setLength(0)
                    val messages = if (round == 1) {
                        history + SseStreamClient.ChatMessage(AiAgentMsg.ROLE_USER, lastUser)
                    } else {
                        history
                    }
                    val toolCalls = streamOneRound(
                        conv = conv,
                        assistantMsgId = assistantMsgId,
                        system = rolledSystem,
                        messages = messages,
                        dialect = dialect,
                        conf = conf,
                        content = content,
                        thinking = thinking
                    )
                    if (toolCalls.isEmpty()) {
                        // 全程无工具 → 收官下一条完整 assistant 消息
                        finalizeAssistant(
                            msgId = assistantMsgId,
                            content = content.toString(),
                            thinking = thinking.toString(),
                            status = AiAgentMsg.STATUS_DONE
                        )
                        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                        break
                    }
                    // 记录 assistant 工具声明(DONE,不再续写)并追加到模型历史
                    val calls = callsJson(toolCalls)
                    val now = System.currentTimeMillis()
                    appDb.aiAgentMsgDao.setToolFields(assistantMsgId, calls, AiAgentMsg.STATUS_DONE, now)
                    appDb.aiAgentMsgDao.setThinking(
                        assistantMsgId,
                        thinking.takeIf { it.isNotEmpty() }?.toString().orEmpty(),
                        now
                    )
                    postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                    history += SseStreamClient.ChatMessage(
                        role = AiAgentMsg.ROLE_ASSISTANT,
                        content = content.takeIf { it.isNotEmpty() }?.toString().orEmpty(),
                        toolCalls = parseCallsJson(calls)
                    )
                    // 执行各工具,结果写 TOOL 消息 + 追加历史
                    toolCalls.forEach { call ->
                        currentCoroutineContext().ensureActive()
                        val result = executeTool(call, conv.id, defaultBookUrl)
                        val toolOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id)
                        val toolMsg = AiAgentMsg(
                            convId = conv.id,
                            sortOrder = toolOrder,
                            role = AiAgentMsg.ROLE_TOOL,
                            content = result,
                            kind = AiAgentMsg.KIND_MESSAGE,
                            toolCallId = call.id,
                            createTime = System.currentTimeMillis(),
                            updateTime = System.currentTimeMillis()
                        )
                        appDb.aiAgentMsgDao.insert(toolMsg)
                        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                        history += SseStreamClient.ChatMessage(
                            role = AiAgentMsg.ROLE_TOOL,
                            content = result,
                            toolCallId = call.id
                        )
                    }
                    if (round >= MAX_ROUNDS) break
                    // 下一轮:新建 assistant 消息
                    assistantMsgId = appDb.aiAgentMsgDao.insert(
                        AiAgentMsg(
                            convId = conv.id,
                            sortOrder = appDb.aiAgentMsgDao.nextSortOrder(conv.id),
                            role = AiAgentMsg.ROLE_ASSISTANT,
                            content = "",
                            kind = AiAgentMsg.KIND_MESSAGE,
                            status = AiAgentMsg.STATUS_STREAMING
                        )
                    ).firstOrNull() ?: break
                }
            }
        } catch (ce: CancellationException) {
            // 中断:已累积 content/thinking 保留,标记 CANCELLED,不写半成品(拆书链路见 streamSingleBreakdown)
            finalizeAssistant(
                msgId = assistantMsgId,
                content = content.toString(),
                thinking = thinking.toString(),
                status = AiAgentMsg.STATUS_CANCELLED
            )
            throw ce
        }
    }

    /** 收官一条 assistant 消息:写入完整 content + thinking,状态置位并广播 */
    private fun finalizeAssistant(msgId: Long, content: String, thinking: String, status: Int) {
        val now = System.currentTimeMillis()
        appDb.aiAgentMsgDao.finalizeContent(
            id = msgId,
            content = content,
            status = status,
            written = false,
            time = now
        )
        if (thinking.isNotEmpty()) {
            appDb.aiAgentMsgDao.setThinking(msgId, thinking, now)
        }
    }

    /**
     * 流式一轮(单次 SSE),把 thinking/content 增量实时 flush 进 DB(每 5 块一次)。
     * @return 本轮解析出的工具调用(可能为空)
     */
    private suspend fun streamOneRound(
        conv: AiAgentConv,
        assistantMsgId: Long,
        system: String,
        messages: List<SseStreamClient.ChatMessage>,
        dialect: String,
        conf: AiModelResolver.Resolved,
        content: StringBuilder,
        thinking: StringBuilder
    ): List<SseStreamClient.AiToolCallDelta> {
        var acc = 0
        var thinkAcc = 0
        var toolCalls: List<SseStreamClient.AiToolCallDelta> = emptyList()
        SseStreamClient.streamChat(
            system = system,
            user = "",
            baseUrl = conf.baseUrl,
            apiKey = conf.apiKey,
            model = conf.model,
            messages = messages,
            jsonMode = false,
            temperature = 0.7,
            tools = ReaderToolset.all.map { it.spec },
            dialect = dialect
        ).collect { ev ->
            currentCoroutineContext().ensureActive()
            when (ev) {
                is SseStreamClient.StreamEvent.Reasoning -> {
                    thinking.append(ev.text)
                    if (++thinkAcc % 5 == 0) {
                        appDb.aiAgentMsgDao.appendThinking(assistantMsgId, ev.text, System.currentTimeMillis())
                        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                    }
                }
                is SseStreamClient.StreamEvent.Delta -> {
                    content.append(ev.chunk)
                    if (++acc % 5 == 0) {
                        appDb.aiAgentMsgDao.appendContent(assistantMsgId, ev.chunk, System.currentTimeMillis())
                        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                    }
                }
                is SseStreamClient.StreamEvent.Done -> toolCalls = ev.toolCalls
                is SseStreamClient.StreamEvent.Error -> throw ev.t
            }
        }
        return toolCalls
    }

    /* ------------------------------ 工具执行 ------------------------------ */

    private fun resolveDefaultBookUrl(conv: AiAgentConv): String? {
        if (conv.refBreakdownId <= 0) return null
        return appDb.bookBreakdownDao.get(conv.refBreakdownId)?.bookUrl
    }

    private suspend fun executeTool(
        call: SseStreamClient.AiToolCallDelta,
        convId: Long,
        defaultBookUrl: String?
    ): String {
        val tool = ReaderToolset.byName(call.name)
            ?: return "工具执行失败:未知工具 ${call.name}"
        val args = kotlin.runCatching {
            com.google.gson.JsonParser.parseString(call.arguments).asJsonObject
        }.getOrElse { com.google.gson.JsonObject() }
        return kotlin.runCatching {
            tool.execute(args, ToolContext(convId, defaultBookUrl))
        }.getOrElse { "工具执行失败:${it.message ?: "未知错误"}" }
    }

    private fun callsJson(calls: List<SseStreamClient.AiToolCallDelta>): String {
        val arr = com.google.gson.JsonArray()
        calls.forEach { c ->
            arr.add(com.google.gson.JsonObject().apply {
                addProperty("id", c.id)
                addProperty("type", "function")
                add("function", com.google.gson.JsonObject().apply {
                    addProperty("name", c.name)
                    addProperty("arguments", c.arguments)
                })
            })
        }
        return com.google.gson.Gson().toJson(arr)
    }

    private fun parseCallsJson(json: String): com.google.gson.JsonArray? {
        return kotlin.runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonArray
        }.getOrNull()
    }

    /* ------------------------------ 前情提要(M4) ------------------------------ */

    /** 组装前情提要并注入 system;仅在触发条件满足时发起 CHEAP 角色请求(失败静默) */
    private suspend fun refreshRollingSummary(convId: Long, systemPrompt: String): String {
        return try {
            val conv = appDb.aiAgentConvDao.get(convId) ?: return systemPrompt
            val msgs = appDb.aiAgentMsgDao.getByConv(convId)
                .filter { it.kind == AiAgentMsg.KIND_MESSAGE }
            val latestId = msgs.lastOrNull()?.id ?: 0L
            if (!RollingSummaryService.shouldRefresh(msgs.size, conv.summarizedThroughMessageId, latestId)) {
                return RollingSummaryService.injectIntoSystem(systemPrompt, conv.rollingSummary)
            }
            // 取水位之后的消息(水位 0 = 全部)
            val newMsgs = if (conv.summarizedThroughMessageId == 0L) {
                msgs
            } else {
                msgs.filter { it.id > conv.summarizedThroughMessageId }
            }.takeLast(RollingSummaryService.MAX_MESSAGES)
            if (newMsgs.isEmpty()) return systemPrompt
            val summary = RollingSummaryService.summarize(conv.rollingSummary, newMsgs) ?: return systemPrompt
            appDb.aiAgentConvDao.updateRollingSummary(
                convId, summary, newMsgs.last().id, System.currentTimeMillis()
            )
            RollingSummaryService.injectIntoSystem(systemPrompt, summary)
        } catch (t: Throwable) {
            systemPrompt
        }
    }

    /**
     * 构建模型侧历史(普通对话)窗口。
     * - 全 kind 参与:CONTEXT_INJECT(注入的章节正文)以 system 消息带入模型,其余系统横幅/错误跳过;
     * - 剔除当前轮 user(其内容由 lastUser 单独提交,避免重复);
     * - 窗口上限 60 条,起点从 size-60 处向前对齐到 user 消息(避免 tool 结果被拦腰截断);窗口外交给前情提要;
     * - includeSystemContext=false 时(claude 方言)丢弃上下文注入块,二期再支持聚合进 system 参数。
     */
    private fun buildLoopHistory(
        convId: Long,
        includeSystemContext: Boolean = true
    ): MutableList<SseStreamClient.ChatMessage> {
        val msgs = appDb.aiAgentMsgDao.getByConv(convId)
        if (msgs.isEmpty()) return mutableListOf()
        // 当前轮 user 是最后一条(调用方已写入),其内容由 lastUser 单独提交,剔除避免重复
        val base = if (msgs.last().role == AiAgentMsg.ROLE_USER) msgs.dropLast(1) else msgs
        if (base.isEmpty()) return mutableListOf()
        // 窗口上限 60;起点从 size-60 处向前对齐到 user,找不到时向后找第一条 user(兼容半截 tool 结果续传)
        val desired = (base.size - 60).coerceAtLeast(0)
        var start = desired
        if (desired > 0) {
            var hit = -1
            for (i in desired downTo 0) if (base[i].role == AiAgentMsg.ROLE_USER) { hit = i; break }
            if (hit < 0) for (i in desired + 1 until base.size) if (base[i].role == AiAgentMsg.ROLE_USER) { hit = i; break }
            if (hit >= 0) start = hit
        }
        val list = mutableListOf<SseStreamClient.ChatMessage>()
        base.subList(start, base.size).forEach { m ->
            when (m.role) {
                AiAgentMsg.ROLE_TOOL -> list.add(
                    SseStreamClient.ChatMessage(AiAgentMsg.ROLE_TOOL, m.content, toolCallId = m.toolCallId)
                )
                AiAgentMsg.ROLE_ASSISTANT -> {
                    val calls = parseCallsJson(m.toolCallsJson)?.takeIf { it.size() > 0 }
                    list.add(SseStreamClient.ChatMessage(AiAgentMsg.ROLE_ASSISTANT, m.content, toolCalls = calls))
                }
                AiAgentMsg.ROLE_SYSTEM -> {
                    // 仅把「上下文注入」正文带给模型;KIND_ERROR 等横幅不参与历史
                    if (includeSystemContext && m.kind == AiAgentMsg.KIND_CONTEXT_INJECT && m.content.isNotBlank()) {
                        list.add(SseStreamClient.ChatMessage(AiAgentMsg.ROLE_SYSTEM, m.content))
                    }
                }
                else -> list.add(SseStreamClient.ChatMessage(m.role, m.content))
            }
        }
        return list
    }
}
