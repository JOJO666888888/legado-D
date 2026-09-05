package io.legado.app.help.ai

import com.google.gson.JsonParser
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * ST 网关客户端(对接 st-gateway-lite 单文件服务)。
 *
 * 三个通道:
 *   1. 同步: 存档列表/存档原文/角色卡封面下载 → 复用 StChatFile 导入链路进书架;
 *   2. 游玩: streamChat SSE 流式对话(消息以 / 开头即命令:/char /load /new 等),
 *      会话状态(角色/预设/世界书/存档)全在 gateway 侧,App 是哑终端;
 *   3. 会话: getSession 拉取 profile+最近历史+开场白,进入游玩页时回显。
 *
 * 鉴权: X-Gateway-Token 头;取消语义: Flow 取消 → okhttp Call cancel(对齐 SseStreamClient 先例)。
 */
object StGatewayClient {

    /** 网关上的存档条目(GET /api/chats) */
    data class StChatInfo(
        val name: String,
        val charName: String,
        val messages: Int,
        val mtime: Long,
        val size: Long
    )

    /** 游玩会话快照(GET /api/session/:id) */
    data class StSession(
        val sessionId: String,
        val character: String,
        val greeting: String?,
        val history: List<StHistoryMsg>,
        val llmConfigured: Boolean
    )

    data class StHistoryMsg(val name: String, val isUser: Boolean, val mes: String)

    /** 游玩流事件:正文增量 / 思维链增量 / 完成(含命令标记) / 错误 */
    sealed class PlayEvent {
        data class Delta(val text: String) : PlayEvent()
        data class Reasoning(val text: String) : PlayEvent()
        data class Done(val text: String, val command: Boolean) : PlayEvent()
        data class Error(val message: String) : PlayEvent()
    }

    private fun baseUrl(): String =
        AppConfig.stGatewayUrl.trim().trimEnd('/').ifBlank {
            throw IllegalArgumentException("未配置 ST 网关地址")
        }

    private fun authHeader(builder: Request.Builder): Request.Builder {
        val token = AppConfig.stGatewayToken.trim()
        if (token.isNotEmpty()) builder.addHeader("X-Gateway-Token", token)
        return builder
    }

    /* ------------------------------ 同步通道 ------------------------------ */

    /** 健康检查: 返回 (ok, 描述)。ok=false 时描述为错误原因 */
    suspend fun healthCheck(url: String, token: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url.trim().trimEnd('/') + "/api/health").build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false to "HTTP ${resp.code}"
                    val body = resp.body?.string() ?: return@withContext false to "空响应"
                    val obj = JsonParser.parseString(body).asJsonObject
                    if (obj.get("ok")?.asBoolean != true) {
                        return@withContext false to "响应异常"
                    }
                    val llm = obj.getAsJsonObject("llm")
                    val configured = llm.get("configured")?.asBoolean == true
                    val assets = obj.getAsJsonObject("assets")
                    val msg = buildString {
                        append("连接成功(")
                        append(obj.get("name")?.asString ?: "st-gateway")
                        append(" v")
                        append(obj.get("version")?.asString ?: "?")
                        append("), 角色卡 ")
                        append(assets?.get("characters")?.asInt ?: 0)
                        append(" · 存档 ")
                        append(assets?.get("chats")?.asInt ?: 0)
                        if (!configured) append("\n注意: 网关 LLM 未配置,游玩不可用(同步不受影响)")
                    }
                    true to msg
                }
            }.getOrElse { false to (it.message ?: "连接失败") }
        }

    /** 存档列表(按角色名过滤可选) */
    suspend fun listChats(): List<StChatInfo> = withContext(Dispatchers.IO) {
        val request = authHeader(Request.Builder().url(baseUrl() + "/api/chats")).build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body?.string() ?: throw IOException("空响应")
            val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("chats")
            arr.mapNotNull { el ->
                el.asJsonObject.let {
                    StChatInfo(
                        name = it.get("name")?.asString ?: return@mapNotNull null,
                        charName = it.get("charName")?.asString.orEmpty(),
                        messages = it.get("messages")?.asInt ?: 0,
                        mtime = it.get("mtime")?.asLong ?: 0L,
                        size = it.get("size")?.asLong ?: 0L
                    )
                }
            }
        }
    }

    /** 下载存档 jsonl 原文(StChatParser 直接解析) */
    suspend fun fetchChat(name: String): ByteArray = withContext(Dispatchers.IO) {
        val safe = java.net.URLEncoder.encode(name, "UTF-8")
        val request = authHeader(Request.Builder().url(baseUrl() + "/api/chats/$safe")).build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "下载存档失败 HTTP ${resp.code}" }
            resp.body?.bytes() ?: throw IOException("空响应")
        }
    }

    /** 下载角色卡 PNG 封面;无 PNG 卡(纯 JSON 卡)返回 null */
    suspend fun fetchCardCover(charName: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val safe = java.net.URLEncoder.encode(charName, "UTF-8")
            val request = authHeader(
                Request.Builder().url(baseUrl() + "/api/cards/$safe/cover")
            ).build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.bytes()
            }
        }.getOrNull()
    }

    /** 上传本地存档到网关(App 导入的 jsonl 推给网关统一管理) */
    suspend fun uploadChat(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val safe = java.net.URLEncoder.encode(name, "UTF-8")
        val body = bytes.toRequestBody(null)
        val request = authHeader(
            Request.Builder().url(baseUrl() + "/api/chats/$safe").post(body)
        ).build()
        okHttpClient.newCall(request).execute().use { resp ->
            resp.isSuccessful
        }
    }

    /* ------------------------------ 会话通道 ------------------------------ */

    /** 拉取游玩会话快照(profile + 最近历史 + 开场白) */
    suspend fun getSession(sessionId: String): StSession = withContext(Dispatchers.IO) {
        val request = authHeader(
            Request.Builder().url(baseUrl() + "/api/session/$sessionId")
        ).build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body?.string() ?: throw IOException("空响应")
            val obj = JsonParser.parseString(body).asJsonObject
            val history = obj.getAsJsonArray("history")?.mapNotNull { el ->
                el.asJsonObject.let {
                    StHistoryMsg(
                        name = it.get("name")?.asString ?: "",
                        isUser = it.get("isUser")?.asBoolean == true,
                        mes = it.get("mes")?.asString ?: ""
                    )
                }
            } ?: emptyList()
            StSession(
                sessionId = obj.get("sessionId")?.asString ?: sessionId,
                character = obj.get("character")?.asString.orEmpty(),
                greeting = obj.get("greeting")?.takeIf { it.isJsonPrimitive }?.asString,
                history = history,
                llmConfigured = obj.getAsJsonObject("llm")?.get("configured")?.asBoolean == true
            )
        }
    }

    /* ------------------------------ 游玩通道(SSE) ------------------------------ */

    /**
     * 流式对话。消息以 / 开头由网关按命令处理(/char /preset /world /load /new /profile /help)。
     * 取消 Flow 即断开连接(游玩页"停止"按钮用)。
     */
    fun streamChat(sessionId: String, message: String): Flow<PlayEvent> = callbackFlow {
        val payload = GSON.toJson(
            mapOf("sessionId" to sessionId, "message" to message)
        )
        val requestBuilder = Request.Builder().apply {
            url(baseUrl() + "/api/chat")
            addHeader("Content-Type", "application/json")
            addHeader("Accept", "text/event-stream")
            postJson(payload)
        }
        val request = authHeader(requestBuilder).build()
        val call = okHttpClient.newCall(request)
        var cancelled = false
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cancelled) return
                trySend(PlayEvent.Error(e.message ?: "连接失败"))
                channel.close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (cancelled) return
                val body = response.body
                if (body == null) {
                    trySend(PlayEvent.Error("空响应"))
                    channel.close()
                    return
                }
                if (!response.isSuccessful) {
                    val errTxt = runCatching { body.string() }.getOrDefault("")
                    trySend(PlayEvent.Error("HTTP ${response.code} ${errTxt.take(200)}"))
                    channel.close()
                    return
                }
                try {
                    val source = body.source()
                    val buffer = StringBuilder()
                    var doneSent = false
                    while (!cancelled) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty() || line.startsWith(":")) continue
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        val ev = runCatching { JsonParser.parseString(data).asJsonObject }
                            .getOrNull() ?: continue
                        when (ev.get("type")?.asString) {
                            "delta" -> {
                                val text = ev.get("text")?.asString.orEmpty()
                                buffer.append(text)
                                trySend(PlayEvent.Delta(text))
                            }

                            "reasoning" -> trySend(
                                PlayEvent.Reasoning(ev.get("text")?.asString.orEmpty())
                            )

                            "done" -> {
                                doneSent = true
                                trySend(
                                    PlayEvent.Done(
                                        text = ev.get("text")?.asString.orEmpty(),
                                        command = ev.get("command")?.asBoolean == true
                                    )
                                )
                            }

                            "error" -> {
                                doneSent = true
                                trySend(PlayEvent.Error(ev.get("message")?.asString ?: "未知错误"))
                            }
                        }
                    }
                    // 流正常关闭但网关异常退出(没发 done):把已收增量兜底为 Done
                    if (!doneSent && !cancelled && buffer.isNotEmpty()) {
                        trySend(PlayEvent.Done(buffer.toString(), command = false))
                    }
                    channel.close()
                } catch (t: Throwable) {
                    if (cancelled) return
                    t.printOnDebug()
                    trySend(PlayEvent.Error(t.message ?: "流读取失败"))
                    channel.close()
                } finally {
                    runCatching { body.close() }
                }
            }
        })
        awaitClose {
            cancelled = true
            runCatching { call.cancel() }
        }
    }.flowOn(Dispatchers.IO)
}
