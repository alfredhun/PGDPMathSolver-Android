package com.pgdp.mathsolver.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.pgdp.mathsolver.ui.ParsedGeometryData
import com.pgdp.mathsolver.ui.SolveEngine
import com.pgdp.mathsolver.ui.SolveMode
import com.pgdp.mathsolver.ui.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class TextLlmProvider(val label: String) {
    DEEPSEEK("DeepSeek 推理"),
    GLM("智谱 GLM 文本")
}

data class LlmApiConfig(
    // 智谱 GLM（支持视觉与文本）
    val glmBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val glmApiKey: String = "",
    val glmVisionModel: String = "glm-4v-flash",
    val glmTextModel: String = "glm-4-flash",
    // DeepSeek（专精纯文本数学推理）
    val deepseekBaseUrl: String = "https://api.deepseek.com/v1",
    val deepseekApiKey: String = "",
    val deepseekModel: String = "deepseek-chat",
    // 「本地+AI」省流模式优先使用的纯文本大模型
    val preferredTextProvider: TextLlmProvider = TextLlmProvider.DEEPSEEK
)

class ApiConfigStore(context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "llm_api_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(): LlmApiConfig {
        val providerName = prefs.getString("preferred_text_provider", TextLlmProvider.DEEPSEEK.name)
        val provider = runCatching { TextLlmProvider.valueOf(providerName ?: "") }
            .getOrDefault(TextLlmProvider.DEEPSEEK)

        return LlmApiConfig(
            glmBaseUrl = prefs.getString("glm_base_url", "https://open.bigmodel.cn/api/paas/v4")
                ?: "https://open.bigmodel.cn/api/paas/v4",
            glmApiKey = prefs.getString("glm_api_key", "") ?: "",
            glmVisionModel = prefs.getString("glm_vision_model", "glm-4v-flash") ?: "glm-4v-flash",
            glmTextModel = prefs.getString("glm_text_model", "glm-4-flash") ?: "glm-4-flash",
            deepseekBaseUrl = prefs.getString("deepseek_base_url", "https://api.deepseek.com/v1")
                ?: "https://api.deepseek.com/v1",
            deepseekApiKey = prefs.getString("deepseek_api_key", "") ?: "",
            deepseekModel = prefs.getString("deepseek_model", "deepseek-chat") ?: "deepseek-chat",
            preferredTextProvider = provider
        )
    }

    fun save(config: LlmApiConfig) {
        prefs.edit()
            .putString("glm_base_url", config.glmBaseUrl.trim().trimEnd('/'))
            .putString("glm_api_key", config.glmApiKey.trim())
            .putString("glm_vision_model", config.glmVisionModel.trim())
            .putString("glm_text_model", config.glmTextModel.trim())
            .putString("deepseek_base_url", config.deepseekBaseUrl.trim().trimEnd('/'))
            .putString("deepseek_api_key", config.deepseekApiKey.trim())
            .putString("deepseek_model", config.deepseekModel.trim())
            .putString("preferred_text_provider", config.preferredTextProvider.name)
            .apply()
    }
}

class HybridSolveEngine(
    private val context: Context,
    private val configStore: ApiConfigStore
) : SolveEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val textRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private fun ensurePython(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        return Python.getInstance()
    }

    private suspend fun runOfflineOcr(imageFile: File): String = runCatching {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return ""
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.process(inputImage).await()
        result.text
    }.getOrDefault("")

    override suspend fun solve(imageFile: File, mode: SolveMode): SolveResult =
        withContext(Dispatchers.IO) {
            when (mode) {
                SolveMode.LOCAL_ONLY -> {
                    val ocrText = runOfflineOcr(imageFile)
                    runLocalSolve(imageFile, ocrText)
                }
                SolveMode.HYBRID_TEXT_LLM -> {
                    val ocrText = runOfflineOcr(imageFile)
                    val parsed = runLocalParseOnly(imageFile, ocrText)
                    escalateToTextLlm(imageFile, parsed)
                }
                SolveMode.CLOUD_VISION_LLM -> runCloudVisionSolve(imageFile)
            }
        }

    override suspend fun escalateToTextLlm(
        imageFile: File,
        parsedGeometry: ParsedGeometryData
    ): SolveResult = withContext(Dispatchers.IO) {
        val cfg = configStore.load()

        // 根据用户选择决定纯文本推理走 DeepSeek 还是 智谱 GLM（若主选 Key 为空则自动回退另一个已配置 Key）
        val (baseUrl, apiKey, model) = when (cfg.preferredTextProvider) {
            TextLlmProvider.DEEPSEEK -> {
                if (cfg.deepseekApiKey.isNotBlank()) {
                    Triple(cfg.deepseekBaseUrl, cfg.deepseekApiKey, cfg.deepseekModel)
                } else {
                    Triple(cfg.glmBaseUrl, cfg.glmApiKey, cfg.glmTextModel)
                }
            }
            TextLlmProvider.GLM -> {
                if (cfg.glmApiKey.isNotBlank()) {
                    Triple(cfg.glmBaseUrl, cfg.glmApiKey, cfg.glmTextModel)
                } else {
                    Triple(cfg.deepseekBaseUrl, cfg.deepseekApiKey, cfg.deepseekModel)
                }
            }
        }
        require(apiKey.isNotBlank()) { "请先在右上角设置中填写 DeepSeek 或智谱 GLM 的 API Key" }

        val systemPrompt = """
            你是一位初高中平面几何解题专家。用户已通过本地 PGDP 引擎提取出题干与形式化几何谓词。
            请严格依据给定条件进行严谨推导，所有公式使用标准 LaTeX（行内用 $...$，独立公式用 $$...$$）。
            只输出合法 JSON，格式如下：
            {
              "final_answer": "...",
              "steps_markdown": "1. ...\n2. ..."
            }
        """.trimIndent()

        val userPrompt = """
            【题干文本】
            ${parsedGeometry.questionText.ifBlank { "（本地未提取到完整文字，请主要依据几何谓词推导）" }}

            【几何点】
            ${parsedGeometry.points.joinToString(", ")}

            【PGDP 形式化谓词】
            ${parsedGeometry.logicForms.joinToString("\n")}
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }

        val rawContent = callChatCompletions(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = messages
        )
        val json = parseJsonObjectSafe(rawContent)

        SolveResult(
            modeUsed = SolveMode.HYBRID_TEXT_LLM,
            finalAnswer = json.optString("final_answer", "见下方解答"),
            stepsMarkdown = json.optString("steps_markdown", rawContent),
            parsedGeometry = parsedGeometry,
            solvedLocally = false,
            localConfidence = 1.0f
        )
    }

    private fun runLocalParseOnly(imageFile: File, ocrText: String): ParsedGeometryData {
        val py = ensurePython()
        val module = py.getModule("local_solver")
        val jsonStr = module.callAttr("parse_only", imageFile.absolutePath, ocrText).toString()
        val obj = JSONObject(jsonStr)
        return parseGeometryFromJson(obj, jsonStr)
    }

    private fun runLocalSolve(imageFile: File, ocrText: String): SolveResult {
        val py = ensurePython()
        val module = py.getModule("local_solver")
        val jsonStr = module.callAttr("parse_and_solve_local", imageFile.absolutePath, ocrText).toString()
        val obj = JSONObject(jsonStr)

        val parsedObj = obj.optJSONObject("parsed_geometry") ?: JSONObject()
        val parsed = parseGeometryFromJson(parsedObj, parsedObj.toString())

        return SolveResult(
            modeUsed = SolveMode.LOCAL_ONLY,
            finalAnswer = obj.optString("final_answer", "本地未能直接闭合求解"),
            stepsMarkdown = obj.optString(
                "steps_markdown",
                "本地已完成图元与谓词提取，可点击下方「用 AI 再解一遍」继续推理。"
            ),
            parsedGeometry = parsed,
            solvedLocally = obj.optBoolean("solved", false),
            localConfidence = obj.optDouble("confidence", 0.5).toFloat()
        )
    }

    private fun runCloudVisionSolve(imageFile: File): SolveResult {
        val cfg = configStore.load()
        require(cfg.glmApiKey.isNotBlank()) { "AI 视觉模式需要智谱 GLM 的 API Key，请先在设置中填写" }

        val b64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        val systemPrompt = """
            你是高精度几何识图与解题系统。请先严格按 PGDP 规范提取图中可见要素，再给出分步解答。
            所有数学公式使用标准 LaTeX（$...$ 或 $$...$$）。只输出合法 JSON：
            {
              "question_text": "...",
              "points": ["A", "B", "C"],
              "diagram_logic_forms": ["PointLiesOnLine(...)"],
              "final_answer": "...",
              "steps_markdown": "1. ...\n2. ..."
            }
        """.trimIndent()

        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", "请识图并分步解答这道几何题。"))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$b64")
                )
            )
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userContent))
        }

        val rawContent = callChatCompletions(
            baseUrl = cfg.glmBaseUrl,
            apiKey = cfg.glmApiKey,
            model = cfg.glmVisionModel,
            messages = messages
        )
        val json = parseJsonObjectSafe(rawContent)
        val parsed = parseGeometryFromJson(json, rawContent)

        return SolveResult(
            modeUsed = SolveMode.CLOUD_VISION_LLM,
            finalAnswer = json.optString("final_answer", "见下方解答"),
            stepsMarkdown = json.optString("steps_markdown", rawContent),
            parsedGeometry = parsed,
            solvedLocally = false,
            localConfidence = 1.0f
        )
    }

    private fun callChatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray
    ): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.1)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("API 请求失败 (${resp.code}): $bodyStr")
            }
            val root = JSONObject(bodyStr)
            return root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun parseGeometryFromJson(obj: JSONObject, raw: String): ParsedGeometryData {
        val pts = obj.optJSONArray("points").toStringList()
        val forms = (obj.optJSONArray("diagram_logic_forms")
            ?: obj.optJSONArray("unified_logic_forms")).toStringList()
        return ParsedGeometryData(
            questionText = obj.optString("question_text", ""),
            points = pts,
            logicForms = forms,
            rawJson = raw
        )
    }

    private fun parseJsonObjectSafe(raw: String): JSONObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { JSONObject(cleaned) }.getOrElse {
            JSONObject().put("steps_markdown", raw)
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { i -> optString(i) }.filter { it.isNotBlank() }
    }
}
