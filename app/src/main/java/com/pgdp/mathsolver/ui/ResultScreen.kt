package com.pgdp.mathsolver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveResultScreen(
    croppedImageFile: File,
    result: SolveResult,
    onRetake: () -> Unit,
    onEscalateToTextLlm: () -> Unit,
    onEscalateToVisionLlm: () -> Unit
) {
    var showLogicForms by remember { mutableStateOf(false) }
    var expandImage by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    val answerGradient = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF1D356B), Color(0xFF28488E)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF2550CE), Color(0xFF4772F5)))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("解题结果", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    AssistChip(
                        onClick = {},
                        label = { Text(result.modeUsed.badgeText) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (result.modeUsed == SolveMode.LOCAL_ONLY) {
                        Button(
                            onClick = onEscalateToTextLlm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("用 AI 再解一遍（复用本地识图，省流）")
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRetake,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("拍下一题")
                        }

                        if (result.modeUsed != SolveMode.CLOUD_VISION_LLM) {
                            OutlinedButton(
                                onClick = onEscalateToVisionLlm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("改用 AI 视觉精解")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 渐变高亮主卡：最终答案 + 一键复制
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(answerGradient)
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "最终答案",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(result.finalAnswer))
                                scope.launch {
                                    snackbarHostState.showSnackbar("已复制答案（含 LaTeX 公式）")
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制答案",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("复制答案", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    KatexMarkdownView(
                        markdownContent = result.finalAnswer,
                        fontSizePx = 19,
                        forceLightText = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    )
                }
            }

            // 2. 可点击展开/收起的裁剪原图预览
            val bmp = remember(croppedImageFile) {
                BitmapFactory.decodeFile(croppedImageFile.absolutePath)
            }
            if (bmp != null) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandImage = !expandImage }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "题目原图",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (expandImage) 420.dp else 140.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (expandImage) "点击收起原图" else "点击查看大图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            // 3. 折叠展示 PGDP 识图提取的题干与形式化谓词
            result.parsedGeometry?.let { parsed ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLogicForms = !showLogicForms },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PGDP 识图与几何谓词（${parsed.logicForms.size} 条）",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Icon(
                                imageVector = if (showLogicForms) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "展开或收起"
                            )
                        }
                        AnimatedVisibility(visible = showLogicForms) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                if (parsed.questionText.isNotBlank()) {
                                    Text(
                                        text = "题干：${parsed.questionText}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                parsed.logicForms.forEach { predicate ->
                                    Text(
                                        text = "• $predicate",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. 详细解题步骤卡片 + 一键复制全文
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(top = 12.dp, start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "详细解题步骤",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                val fullText = "答案：${result.finalAnswer}\n\n步骤：\n${result.stepsMarkdown}"
                                clipboard.setText(AnnotatedString(fullText))
                                scope.launch {
                                    snackbarHostState.showSnackbar("已复制完整解题步骤")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制步骤",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("复制全文", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    KatexMarkdownView(
                        markdownContent = result.stepsMarkdown,
                        fontSizePx = 15,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KatexMarkdownView(
    markdownContent: String,
    fontSizePx: Int = 15,
    forceLightText: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasOfflineAssets = remember(context) {
        assetExists(context, "katex/katex.min.js") &&
            assetExists(context, "katex/katex.min.css") &&
            assetExists(context, "katex/contrib/auto-render.min.js") &&
            assetExists(context, "marked.min.js")
    }

    val htmlBody = remember(markdownContent, hasOfflineAssets, fontSizePx, forceLightText) {
        val katexCssUrl = if (hasOfflineAssets) {
            "file:///android_asset/katex/katex.min.css"
        } else {
            "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css"
        }
        val markedJsUrl = if (hasOfflineAssets) {
            "file:///android_asset/marked.min.js"
        } else {
            "https://cdn.jsdelivr.net/npm/marked/marked.min.js"
        }
        val katexJsUrl = if (hasOfflineAssets) {
            "file:///android_asset/katex/katex.min.js"
        } else {
            "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
        }
        val autoRenderJsUrl = if (hasOfflineAssets) {
            "file:///android_asset/katex/contrib/auto-render.min.js"
        } else {
            "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
        }

        val textCss = if (forceLightText) {
            "color: #ffffff;"
        } else {
            """
            color: #1d2024;
            @media (prefers-color-scheme: dark) { body { color: #e6e8ec; } }
            """.trimIndent()
        }

        val escapedJson = JSONObject.quote(markdownContent)
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <link rel="stylesheet" href="$katexCssUrl"/>
          <script src="$markedJsUrl"></script>
          <script src="$katexJsUrl"></script>
          <script src="$autoRenderJsUrl"></script>
          <style>
            :root { color-scheme: light dark; }
            body {
              font-family: -apple-system, sans-serif;
              font-size: ${fontSizePx}px;
              line-height: 1.65;
              $textCss
              padding: 4px 2px;
              margin: 0;
            }
            p { margin: 4px 0; }
            .katex-display { overflow-x: auto; padding: 4px 0; }
          </style>
        </head>
        <body>
          <div id="content"></div>
          <script>
            const md = $escapedJson;
            const el = document.getElementById('content');
            el.innerHTML = typeof marked !== 'undefined' ? marked.parse(md) : md;
            if (typeof renderMathInElement !== 'undefined') {
              renderMathInElement(el, {
                delimiters: [
                  {left: '$$', right: '$$', display: true},
                  {left: '\\[', right: '\\]', display: true},
                  {left: '$', right: '$', display: false},
                  {left: '\\(', right: '\\)', display: false}
                ],
                throwOnError: false
              });
            }
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                htmlBody,
                "text/html",
                "utf-8",
                null
            )
        }
    )
}

private fun assetExists(context: Context, path: String): Boolean {
    return runCatching {
        context.assets.open(path).close()
        true
    }.getOrDefault(false)
}
