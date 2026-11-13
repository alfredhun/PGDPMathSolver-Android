package com.pgdp.mathsolver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pgdp.mathsolver.data.ApiConfigStore
import com.pgdp.mathsolver.data.HybridSolveEngine
import com.pgdp.mathsolver.data.LlmApiConfig
import com.pgdp.mathsolver.data.TextLlmProvider
import com.pgdp.mathsolver.ui.*

private val EyeCareLightColors = lightColorScheme(
    primary = Color(0xFF2855D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF),
    onPrimaryContainer = Color(0xFF0E2568),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF1D2024),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D2024),
    surfaceVariant = Color(0xFFF1EFE9),
    onSurfaceVariant = Color(0xFF5A5E66)
)

private val EyeCareDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF08224F),
    primaryContainer = Color(0xFF1B3263),
    onPrimaryContainer = Color(0xFFD6E4FF),
    background = Color(0xFF13151A),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF1B1E25),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFA0A6B2)
)

@Composable
fun MathSolverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) EyeCareDarkColors else EyeCareLightColors,
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configStore = ApiConfigStore(applicationContext)
        val solveEngine = HybridSolveEngine(applicationContext, configStore)
        val viewModel = SolveViewModel(solveEngine)

        setContent {
            MathSolverTheme {
                MathSolverApp(
                    viewModel = viewModel,
                    configStore = configStore
                )
            }
        }
    }
}

@Composable
fun MathSolverApp(
    viewModel: SolveViewModel,
    configStore: ApiConfigStore
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is SolveUiState.Capturing -> {
                CameraCaptureScreen(
                    selectedMode = selectedMode,
                    onModeSelected = viewModel::selectMode,
                    onImageReady = viewModel::onImageCaptured
                )
            }

            is SolveUiState.Cropping -> {
                ImageCropScreen(
                    bitmap = state.bitmap,
                    selectedMode = selectedMode,
                    onModeSelected = viewModel::selectMode,
                    onCancel = viewModel::retakePhoto,
                    onConfirmCrop = { croppedFile, mode ->
                        viewModel.submitCroppedImage(croppedFile, mode)
                    }
                )
            }

            is SolveUiState.Loading -> {
                PolishedLoadingView(mode = state.mode, message = state.message)
            }

            is SolveUiState.Success -> {
                SolveResultScreen(
                    croppedImageFile = state.croppedImageFile,
                    result = state.result,
                    onRetake = viewModel::retakePhoto,
                    onEscalateToTextLlm = { viewModel.escalateCurrentResult(forceVision = false) },
                    onEscalateToVisionLlm = { viewModel.escalateCurrentResult(forceVision = true) }
                )
            }

            is SolveUiState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.padding(24.dp).fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = "解题遇到了一点问题",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(20.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = viewModel::retakePhoto,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("重新拍照")
                                    }
                                    if (state.croppedImageFile != null) {
                                        Button(
                                            onClick = {
                                                viewModel.submitCroppedImage(
                                                    state.croppedImageFile,
                                                    state.lastMode
                                                )
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uiState is SolveUiState.Capturing) {
            FilledIconButton(
                onClick = { showSettingsDialog = true },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.48f),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "API 设置"
                )
            }
        }

        if (showSettingsDialog) {
            ApiSettingsDialog(
                initialConfig = remember { configStore.load() },
                onDismiss = { showSettingsDialog = false },
                onSave = { updated ->
                    configStore.save(updated)
                    showSettingsDialog = false
                }
            )
        }
    }
}

@Composable
private fun PolishedLoadingView(
    mode: SolveMode,
    message: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .scale(pulseScale)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF2855D6), Color(0xFF5B86FF))
                                ),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(mode.badgeText) }
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LoadingStepRow("图像畸变校正与裁剪", done = true)
                        LoadingStepRow("提取题干文字与 PGDP 几何谓词", done = mode == SolveMode.CLOUD_VISION_LLM)
                        LoadingStepRow("符号方程 / 逻辑推理求解", done = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingStepRow(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsDialog(
    initialConfig: LlmApiConfig,
    onDismiss: () -> Unit,
    onSave: (LlmApiConfig) -> Unit
) {
    var glmBaseUrl by remember { mutableStateOf(initialConfig.glmBaseUrl) }
    var glmApiKey by remember { mutableStateOf(initialConfig.glmApiKey) }
    var glmVisionModel by remember { mutableStateOf(initialConfig.glmVisionModel) }
    var glmTextModel by remember { mutableStateOf(initialConfig.glmTextModel) }

    var deepseekBaseUrl by remember { mutableStateOf(initialConfig.deepseekBaseUrl) }
    var deepseekApiKey by remember { mutableStateOf(initialConfig.deepseekApiKey) }
    var deepseekModel by remember { mutableStateOf(initialConfig.deepseekModel) }
    var preferredProvider by remember { mutableStateOf(initialConfig.preferredTextProvider) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("双引擎 API 设置（GLM + DeepSeek）") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "「本地+AI」纯文本推理引擎",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TextLlmProvider.entries.forEachIndexed { idx, provider ->
                        SegmentedButton(
                            selected = preferredProvider == provider,
                            onClick = { preferredProvider = provider },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = TextLlmProvider.entries.size
                            ),
                            label = { Text(provider.label) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "智谱 GLM（视觉精解 + 文本备选）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = glmApiKey,
                    onValueChange = { glmApiKey = it },
                    label = { Text("GLM API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = glmVisionModel,
                    onValueChange = { glmVisionModel = it },
                    label = { Text("视觉模型（如 glm-4v-flash / glm-4.6v-flashx）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = glmTextModel,
                    onValueChange = { glmTextModel = it },
                    label = { Text("GLM 文本模型（如 glm-4-flash）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = glmBaseUrl,
                    onValueChange = { glmBaseUrl = it },
                    label = { Text("GLM Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "DeepSeek（纯文本数学推理）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = deepseekApiKey,
                    onValueChange = { deepseekApiKey = it },
                    label = { Text("DeepSeek API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = deepseekModel,
                    onValueChange = { deepseekModel = it },
                    label = { Text("模型（如 deepseek-chat / deepseek-reasoner）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = deepseekBaseUrl,
                    onValueChange = { deepseekBaseUrl = it },
                    label = { Text("DeepSeek Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        LlmApiConfig(
                            glmBaseUrl = glmBaseUrl,
                            glmApiKey = glmApiKey,
                            glmVisionModel = glmVisionModel,
                            glmTextModel = glmTextModel,
                            deepseekBaseUrl = deepseekBaseUrl,
                            deepseekApiKey = deepseekApiKey,
                            deepseekModel = deepseekModel,
                            preferredTextProvider = preferredProvider
                        )
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
