package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig

/**
 * 角色化模型分配器。
 *
 * 三种角色,每个角色独立 (baseUrl, apiKey, model):
 *   - CHAT     :主对话/拆书(即主配置 aiBaseUrl/aiApiKey/aiModel)
 *   - CHEAP    :便宜模型(前情提要 Rolling Summary 等廉token任务),未配置回退 CHAT
 *   - EMBEDDING:向量模型(/embeddings),未配置回退 CHAT(同端点;若模型本身不支持则接口报错,上层静默降级)
 *
 * D6:全部字段带回退,老用户零配置升级无感知。
 */
object AiModelResolver {

    enum class Role { CHAT, CHEAP, EMBEDDING }

    data class Resolved(val baseUrl: String, val apiKey: String, val model: String)

    fun resolve(role: Role): Resolved = when (role) {
        Role.CHAT -> Resolved(AppConfig.aiBaseUrl, AppConfig.aiApiKey, AppConfig.aiModel)
        Role.CHEAP -> Resolved(
            AppConfig.aiCheapBaseUrl.ifBlank { AppConfig.aiBaseUrl },
            AppConfig.aiCheapApiKey.ifBlank { AppConfig.aiApiKey },
            AppConfig.aiCheapModel.ifBlank { AppConfig.aiModel }
        )
        Role.EMBEDDING -> Resolved(
            AppConfig.aiEmbeddingBaseUrl.ifBlank { AppConfig.aiBaseUrl },
            AppConfig.aiEmbeddingApiKey.ifBlank { AppConfig.aiApiKey },
            AppConfig.aiEmbeddingModel.ifBlank { AppConfig.aiModel }
        )
    }

    /** 该角色是否显式配置了专用模型(用于 UI 提示"未配置时回退主配置") */
    fun isExplicitlyConfigured(role: Role): Boolean = when (role) {
        Role.CHAT -> true
        Role.CHEAP -> AppConfig.aiCheapModel.isNotBlank() || AppConfig.aiCheapBaseUrl.isNotBlank()
        Role.EMBEDDING -> AppConfig.aiEmbeddingModel.isNotBlank() || AppConfig.aiEmbeddingBaseUrl.isNotBlank()
    }
}