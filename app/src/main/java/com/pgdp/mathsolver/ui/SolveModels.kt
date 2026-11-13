package com.pgdp.mathsolver.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 用户可选的三种解题模式：
 * - LOCAL_ONLY: 完全离线，端侧 PGDP + OCR + SymPy
 * - HYBRID_TEXT_LLM: 端侧识图提取题干与谓词，仅传文本/JSON 给云端推理模型
 * - CLOUD_VISION_LLM: 直接上传裁剪后的原图给多模态视觉大模型
 */
enum class SolveMode(
    val label: String,
    val badgeText: String,
    val shortHint: String
) {
    LOCAL_ONLY(
        label = "纯本地",
        badgeText = "本地求解 · 零流量",
        shortHint = "端侧识图 + SymPy 符号计算"
    ),
    HYBRID_TEXT_LLM(
        label = "本地+AI",
        badgeText = "AI 文本推理 · 省流",
        shortHint = "端侧提取题干与谓词，仅传文本给大模型"
    ),
    CLOUD_VISION_LLM(
        label = "AI视觉",
        badgeText = "AI 视觉精解",
        shortHint = "直接上传裁剪图给多模态大模型"
    )
}

data class ParsedGeometryData(
    val questionText: String,
    val points: List<String> = emptyList(),
    val logicForms: List<String> = emptyList(),
    val rawJson: String = ""
)

data class SolveResult(
    val modeUsed: SolveMode,
    val finalAnswer: String,
    val stepsMarkdown: String,
    val parsedGeometry: ParsedGeometryData? = null,
    val solvedLocally: Boolean = modeUsed == SolveMode.LOCAL_ONLY,
    val localConfidence: Float = 1.0f
)

sealed interface SolveUiState {
    data object Capturing : SolveUiState
    data class Cropping(val imageFile: File, val bitmap: Bitmap) : SolveUiState
    data class Loading(val mode: SolveMode, val message: String) : SolveUiState
    data class Success(
        val croppedImageFile: File,
        val result: SolveResult
    ) : SolveUiState
    data class Error(
        val croppedImageFile: File?,
        val lastMode: SolveMode,
        val message: String
    ) : SolveUiState
}

/**
 * 统一求解引擎接口：后续可由 Chaquopy + Retrofit 实现注入
 */
interface SolveEngine {
    suspend fun solve(imageFile: File, mode: SolveMode): SolveResult
    suspend fun escalateToTextLlm(
        imageFile: File,
        parsedGeometry: ParsedGeometryData
    ): SolveResult
}

class SolveViewModel(
    private val solveEngine: SolveEngine
) : ViewModel() {

    private val _selectedMode = MutableStateFlow(SolveMode.HYBRID_TEXT_LLM)
    val selectedMode: StateFlow<SolveMode> = _selectedMode.asStateFlow()

    private val _uiState = MutableStateFlow<SolveUiState>(SolveUiState.Capturing)
    val uiState: StateFlow<SolveUiState> = _uiState.asStateFlow()

    fun selectMode(mode: SolveMode) {
        _selectedMode.value = mode
    }

    fun onImageCaptured(imageFile: File, bitmap: Bitmap) {
        _uiState.value = SolveUiState.Cropping(imageFile = imageFile, bitmap = bitmap)
    }

    fun retakePhoto() {
        _uiState.value = SolveUiState.Capturing
    }

    fun submitCroppedImage(croppedFile: File, mode: SolveMode = _selectedMode.value) {
        _selectedMode.value = mode
        viewModelScope.launch {
            _uiState.value = SolveUiState.Loading(
                mode = mode,
                message = when (mode) {
                    SolveMode.LOCAL_ONLY -> "正在本地识别图元与符号求解..."
                    SolveMode.HYBRID_TEXT_LLM -> "正在本地提取逻辑谓词并请求 AI 推理..."
                    SolveMode.CLOUD_VISION_LLM -> "正在上传题目图片至视觉大模型..."
                }
            )
            runCatching {
                solveEngine.solve(croppedFile, mode)
            }.onSuccess { result ->
                _uiState.value = SolveUiState.Success(
                    croppedImageFile = croppedFile,
                    result = result
                )
            }.onFailure { err ->
                _uiState.value = SolveUiState.Error(
                    croppedImageFile = croppedFile,
                    lastMode = mode,
                    message = err.message ?: "解题失败，请重试"
                )
            }
        }
    }

    /**
     * 结果页一键转交 AI：
     * - 若当前是纯本地模式且已有解析出的结构化几何数据，直接复用 JSON 走文本推理（不重复拍照/OCR）
     * - 否则直接用裁剪图升级到 AI 视觉精解
     */
    fun escalateCurrentResult(forceVision: Boolean = false) {
        val current = _uiState.value as? SolveUiState.Success ?: return
        val parsed = current.result.parsedGeometry

        if (forceVision || parsed == null || parsed.questionText.isBlank()) {
            submitCroppedImage(current.croppedImageFile, SolveMode.CLOUD_VISION_LLM)
            return
        }

        viewModelScope.launch {
            _uiState.value = SolveUiState.Loading(
                mode = SolveMode.HYBRID_TEXT_LLM,
                message = "正在将本地提取的题干与几何谓词转交 AI 深度解析..."
            )
            runCatching {
                solveEngine.escalateToTextLlm(current.croppedImageFile, parsed)
            }.onSuccess { upgradedResult ->
                _uiState.value = SolveUiState.Success(
                    croppedImageFile = current.croppedImageFile,
                    result = upgradedResult
                )
            }.onFailure { err ->
                _uiState.value = SolveUiState.Error(
                    croppedImageFile = current.croppedImageFile,
                    lastMode = SolveMode.HYBRID_TEXT_LLM,
                    message = err.message ?: "转交 AI 失败，请检查网络或 API Key"
                )
            }
        }
    }
}
