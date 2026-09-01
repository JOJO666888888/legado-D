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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    /* ------------------------------ 普通对话 ------------------------------ */

    /**
     * 发起普通多轮对话。historyMessages 读取 DB 里该 conv 的消息(user/assistant 成对),
     * 用于构造上下文;最后一条 user 消息为 caller 已写入的最新用户提问。
     */
    fun startChat(
        scope: CoroutineScope,
        conv: AiAgentConv,
        systemPrompt: String,
        historyMessages: List<Pair<String, String>>,
        lastUser: String
    ): Long {
        val order = appDb.aiAgentMsgDao.nextSortOrder(conv.id)
        val assistantMsgId = appDb.aiAgentMsgDao.insert(
            AiAgentMsg(
                convId = conv.id,
                sortOrder = order,
                role = AiAgentMsg.ROLE_ASSISTANT,
                content = "",
                kind = AiAgentMsg.KIND_MESSAGE,
                status = AiAgentMsg.STATUS_STREAMING
            )
        ).firstOrNull() ?: return -1L
        appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_STREAMING, System.currentTimeMillis())
        postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
        val job = scope.launch(Dispatchers.IO) {
            try {
                val full = StringBuilder(4_096)
                var acc = 0
                kotlinx.coroutines.supervisorScope {
                    SseStreamClient.streamChat(
                        system = systemPrompt,
                        user = lastUser,
                        baseUrl = AppConfig.aiBaseUrl,
                        apiKey = AppConfig.aiApiKey,
                        model = AppConfig.aiModel,
                        messages = historyMessages,
                        jsonMode = false,
                        temperature = 0.7
                    ).collect { ev ->
                        when (ev) {
                            is SseStreamClient.StreamEvent.Delta -> {
                                full.append(ev.chunk)
                                if (++acc % 5 == 0) {
                                    appDb.aiAgentMsgDao.appendContent(
                                        assistantMsgId, ev.chunk, System.currentTimeMillis()
                                    )
                                    postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                                }
                            }
                            is SseStreamClient.StreamEvent.Done -> {
                                val tail = if (ev.fullContent.length > full.length) {
                                    ev.fullContent.substring(full.length)
                                } else ""
                                if (tail.isNotEmpty()) full.append(tail)
                                appDb.aiAgentMsgDao.finalizeContent(
                                    id = assistantMsgId,
                                    content = full.toString(),
                                    status = AiAgentMsg.STATUS_DONE,
                                    written = false,
                                    time = System.currentTimeMillis()
                                )
                                appDb.aiAgentConvDao.setState(conv.id, AiAgentConv.STATE_IDLE, System.currentTimeMillis())
                            }
                            is SseStreamClient.StreamEvent.Error -> throw ev.t
                        }
                    }
                }
            } catch (_: CancellationException) {
                appDb.aiAgentMsgDao.finalizeContent(
                    id = assistantMsgId,
                    content = "",
                    status = AiAgentMsg.STATUS_CANCELLED,
                    written = false,
                    time = System.currentTimeMillis()
                )
            } catch (t: Throwable) {
                appDb.aiAgentConvDao.setError(conv.id, t.message ?: "对话失败", System.currentTimeMillis())
                appDb.aiAgentMsgDao.finalizeContent(
                    id = assistantMsgId,
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
                postEvent(EventBus.AI_AGENT_MSG_UPDATED, conv.id.toString())
                postEvent(EventBus.AI_AGENT_CONV_CHANGED, conv.id.toString())
                jobs.remove(conv.id)
            }
        }
        jobs[conv.id] = job
        job.invokeOnCompletion { jobs.remove(conv.id) }
        return assistantMsgId
    }
}
