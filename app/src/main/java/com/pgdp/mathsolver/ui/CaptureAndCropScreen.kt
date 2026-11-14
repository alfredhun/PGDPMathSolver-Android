package com.pgdp.mathsolver.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraCaptureScreen(
    selectedMode: SolveMode,
    onModeSelected: (SolveMode) -> Unit,
    onImageReady: (File, Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (file, bmp) = copyUriToTempFileAndDecode(context, uri)
            if (file != null && bmp != null) {
                onImageReady(file, bmp)
            }
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        cameraProvider.unbindAll()
                        cameraRef = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            // 取景框中央 L 形引导角标
            CameraViewfinderOverlay(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .align(Alignment.Center)
                    .offset(y = (-40).dp)
                    .padding(horizontal = 32.dp)
            )

            // 顶部左侧：闪光灯开关；中央：品牌胶囊标题
            FilledIconButton(
                onClick = {
                    val cam = cameraRef ?: return@FilledIconButton
                    if (cam.cameraInfo.hasFlashUnit()) {
                        isFlashOn = !isFlashOn
                        cam.cameraControl.enableTorch(isFlashOn)
                    }
                },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.48f),
                    contentColor = if (isFlashOn) Color(0xFFFFD54F) else Color.White
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (isFlashOn) "关闭闪光灯" else "开启闪光灯"
                )
            }

            Surface(
                color = Color.Black.copy(alpha = 0.48f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 20.dp)
            ) {
                Text(
                    text = "PGDP 几何识图解题",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要相机权限以拍摄几何题目", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            }
        }

        // 底部毛玻璃风格控制面板：三档模式切换 + 相册 + 拍照按钮
        Surface(
            color = Color(0xD912141A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SolveModeSegmentedBar(
                    selectedMode = selectedMode,
                    onModeSelected = onModeSelected
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = selectedMode.shortHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "从相册选择"
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                            .padding(5.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                                imageCapture.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            val bmp = decodeRotatedBitmap(photoFile)
                                            if (bmp != null) onImageReady(photoFile, bmp)
                                        }
                                        override fun onError(exc: ImageCaptureException) {
                                            exc.printStackTrace()
                                        }
                                    }
                                )
                            },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "拍照",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}

@Composable
private fun CameraViewfinderOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val len = 24.dp.toPx()
            val stroke = 3.dp.toPx()
            val color = Color.White.copy(alpha = 0.78f)
            val w = size.width
            val h = size.height

            // 左上角
            drawLine(color, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
            // 右上角
            drawLine(color, Offset(w - len, 0f), Offset(w, 0f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, 0f), Offset(w, len), stroke, StrokeCap.Round)
            // 左下角
            drawLine(color, Offset(0f, h - len), Offset(0f, h), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, h), Offset(len, h), stroke, StrokeCap.Round)
            // 右下角
            drawLine(color, Offset(w - len, h), Offset(w, h), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, h - len), Offset(w, h), stroke, StrokeCap.Round)
        }
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = "将单道题目置于框内，拍完可二次裁剪",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun ImageCropScreen(
    bitmap: Bitmap,
    selectedMode: SolveMode,
    onModeSelected: (SolveMode) -> Unit,
    onCancel: () -> Unit,
    onConfirmCrop: (File, SolveMode) -> Unit
) {
    val context = LocalContext.current
    var currentBitmap by remember(bitmap) { mutableStateOf(bitmap) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val defaultRect = remember { Rect(left = 0.05f, top = 0.05f, right = 0.95f, bottom = 0.95f) }
    var cropRect by remember(currentBitmap) { mutableStateOf(defaultRect) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "重拍", tint = Color.White)
            }
            Text(
                text = "框选单道几何题目",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Row {
                IconButton(onClick = { cropRect = defaultRect }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重置框选", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        currentBitmap = rotateBitmap90(currentBitmap)
                        cropRect = defaultRect
                    }
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "旋转90度", tint = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { containerSize = it }
        ) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "待裁剪题目",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            val displayedImageRect = remember(containerSize, currentBitmap) {
                calculateDisplayedImageRect(containerSize, currentBitmap)
            }

            if (displayedImageRect.width > 0f && displayedImageRect.height > 0f) {
                CropOverlayCanvas(
                    containerSize = containerSize,
                    imageBounds = displayedImageRect,
                    cropRect = cropRect,
                    onCropRectChange = { cropRect = it }
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Text(
                    text = "选择解题引擎",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SolveModeSegmentedBar(
                    selectedMode = selectedMode,
                    onModeSelected = onModeSelected
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = selectedMode.shortHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val croppedFile = saveCroppedBitmap(context, currentBitmap, cropRect)
                        onConfirmCrop(croppedFile, selectedMode)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("开始识图解题（${selectedMode.label}）")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveModeSegmentedBar(
    selectedMode: SolveMode,
    onModeSelected: (SolveMode) -> Unit
) {
    val modes = SolveMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = {
                    Text(
                        text = mode.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun CropOverlayCanvas(
    containerSize: IntSize,
    imageBounds: Rect,
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit
) {
    val currentCropRect by rememberUpdatedState(cropRect)
    val currentOnChange by rememberUpdatedState(onCropRectChange)
    var activeHandle by remember { mutableStateOf<String?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageBounds) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val r = currentCropRect
                        val bLeft = imageBounds.left + r.left * imageBounds.width
                        val bTop = imageBounds.top + r.top * imageBounds.height
                        val bRight = imageBounds.left + r.right * imageBounds.width
                        val bBottom = imageBounds.top + r.bottom * imageBounds.height

                        val touchRadius = 38.dp.toPx()
                        val distTL = hypot(offset.x - bLeft, offset.y - bTop)
                        val distTR = hypot(offset.x - bRight, offset.y - bTop)
                        val distBL = hypot(offset.x - bLeft, offset.y - bBottom)
                        val distBR = hypot(offset.x - bRight, offset.y - bBottom)

                        activeHandle = when {
                            distTL <= touchRadius -> "TL"
                            distTR <= touchRadius -> "TR"
                            distBL <= touchRadius -> "BL"
                            distBR <= touchRadius -> "BR"
                            abs(offset.y - bTop) <= touchRadius && offset.x in (bLeft - touchRadius)..(bRight + touchRadius) -> "TOP"
                            abs(offset.y - bBottom) <= touchRadius && offset.x in (bLeft - touchRadius)..(bRight + touchRadius) -> "BOTTOM"
                            abs(offset.x - bLeft) <= touchRadius && offset.y in (bTop - touchRadius)..(bBottom + touchRadius) -> "LEFT"
                            abs(offset.x - bRight) <= touchRadius && offset.y in (bTop - touchRadius)..(bBottom + touchRadius) -> "RIGHT"
                            offset.x in bLeft..bRight && offset.y in bTop..bBottom -> "MOVE"
                            else -> null
                        }
                    },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val handle = activeHandle ?: return@detectDragGestures
                        val dxNorm = dragAmount.x / imageBounds.width
                        val dyNorm = dragAmount.y / imageBounds.height
                        val minSize = 0.08f
                        val r = currentCropRect

                        val updated = when (handle) {
                            "TL" -> r.copy(
                                left = (r.left + dxNorm).coerceIn(0f, r.right - minSize),
                                top = (r.top + dyNorm).coerceIn(0f, r.bottom - minSize)
                            )
                            "TR" -> r.copy(
                                right = (r.right + dxNorm).coerceIn(r.left + minSize, 1f),
                                top = (r.top + dyNorm).coerceIn(0f, r.bottom - minSize)
                            )
                            "BL" -> r.copy(
                                left = (r.left + dxNorm).coerceIn(0f, r.right - minSize),
                                bottom = (r.bottom + dyNorm).coerceIn(r.top + minSize, 1f)
                            )
                            "BR" -> r.copy(
                                right = (r.right + dxNorm).coerceIn(r.left + minSize, 1f),
                                bottom = (r.bottom + dyNorm).coerceIn(r.top + minSize, 1f)
                            )
                            "TOP" -> r.copy(
                                top = (r.top + dyNorm).coerceIn(0f, r.bottom - minSize)
                            )
                            "BOTTOM" -> r.copy(
                                bottom = (r.bottom + dyNorm).coerceIn(r.top + minSize, 1f)
                            )
                            "LEFT" -> r.copy(
                                left = (r.left + dxNorm).coerceIn(0f, r.right - minSize)
                            )
                            "RIGHT" -> r.copy(
                                right = (r.right + dxNorm).coerceIn(r.left + minSize, 1f)
                            )
                            "MOVE" -> {
                                val rw = r.width
                                val rh = r.height
                                val nl = (r.left + dxNorm).coerceIn(0f, 1f - rw)
                                val nt = (r.top + dyNorm).coerceIn(0f, 1f - rh)
                                Rect(nl, nt, nl + rw, nt + rh)
                            }
                            else -> r
                        }
                        currentOnChange(updated)
                    }
                )
            }
    ) {
        val totalW = containerSize.width.toFloat()
        val totalH = containerSize.height.toFloat()

        val left = imageBounds.left + cropRect.left * imageBounds.width
        val top = imageBounds.top + cropRect.top * imageBounds.height
        val right = imageBounds.left + cropRect.right * imageBounds.width
        val bottom = imageBounds.top + cropRect.bottom * imageBounds.height
        val boxW = right - left
        val boxH = bottom - top

        val maskPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, totalW, totalH))
            addRect(Rect(left, top, right, bottom))
        }
        drawPath(maskPath, color = Color.Black.copy(alpha = 0.62f))

        // 九宫格辅助线
        val gridColor = Color.White.copy(alpha = 0.28f)
        drawLine(gridColor, Offset(left + boxW / 3f, top), Offset(left + boxW / 3f, bottom), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(left + 2 * boxW / 3f, top), Offset(left + 2 * boxW / 3f, bottom), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(left, top + boxH / 3f), Offset(right, top + boxH / 3f), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(left, top + 2 * boxH / 3f), Offset(right, top + 2 * boxH / 3f), strokeWidth = 1.dp.toPx())

        // 边框
        drawRect(
            color = Color(0xFF8AB4F8),
            topLeft = Offset(left, top),
            size = Size(boxW, boxH),
            style = Stroke(width = 2.dp.toPx())
        )

        // 四角粗 L 形手柄
        val arm = 22.dp.toPx()
        val stroke = 4.dp.toPx()
        val accent = Color.White
        // TL
        drawLine(accent, Offset(left, top), Offset(left + arm, top), stroke, StrokeCap.Round)
        drawLine(accent, Offset(left, top), Offset(left, top + arm), stroke, StrokeCap.Round)
        // TR
        drawLine(accent, Offset(right - arm, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(accent, Offset(right, top), Offset(right, top + arm), stroke, StrokeCap.Round)
        // BL
        drawLine(accent, Offset(left, bottom - arm), Offset(left, bottom), stroke, StrokeCap.Round)
        drawLine(accent, Offset(left, bottom), Offset(left + arm, bottom), stroke, StrokeCap.Round)
        // BR
        drawLine(accent, Offset(right - arm, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
        drawLine(accent, Offset(right, bottom - arm), Offset(right, bottom), stroke, StrokeCap.Round)

        // 四边中心拖拽小横/竖条指示手柄
        val barLen = 24.dp.toPx()
        val barStroke = 3.dp.toPx()
        drawLine(accent, Offset(left + boxW / 2 - barLen / 2, top), Offset(left + boxW / 2 + barLen / 2, top), barStroke, StrokeCap.Round)
        drawLine(accent, Offset(left + boxW / 2 - barLen / 2, bottom), Offset(left + boxW / 2 + barLen / 2, bottom), barStroke, StrokeCap.Round)
        drawLine(accent, Offset(left, top + boxH / 2 - barLen / 2), Offset(left, top + boxH / 2 + barLen / 2), barStroke, StrokeCap.Round)
        drawLine(accent, Offset(right, top + boxH / 2 - barLen / 2), Offset(right, top + boxH / 2 + barLen / 2), barStroke, StrokeCap.Round)
    }
}

private fun calculateDisplayedImageRect(containerSize: IntSize, bitmap: Bitmap): Rect {
    if (containerSize.width <= 0 || containerSize.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
        return Rect.Zero
    }
    val scale = min(
        containerSize.width.toFloat() / bitmap.width.toFloat(),
        containerSize.height.toFloat() / bitmap.height.toFloat()
    )
    val dispW = bitmap.width * scale
    val dispH = bitmap.height * scale
    val left = (containerSize.width - dispW) / 2f
    val top = (containerSize.height - dispH) / 2f
    return Rect(left, top, left + dispW, top + dispH)
}

private fun rotateBitmap90(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun saveCroppedBitmap(context: Context, source: Bitmap, normRect: Rect): File {
    val x = (normRect.left * source.width).toInt().coerceIn(0, source.width - 1)
    val y = (normRect.top * source.height).toInt().coerceIn(0, source.height - 1)
    val cw = (normRect.width * source.width).toInt().coerceIn(1, source.width - x)
    val ch = (normRect.height * source.height).toInt().coerceIn(1, source.height - y)

    val cropped = Bitmap.createBitmap(source, x, y, cw, ch)
    val maxSide = 1600
    val scale = min(1f, maxSide.toFloat() / max(cropped.width, cropped.height))
    val finalBmp = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt(),
            (cropped.height * scale).toInt(),
            true
        )
    } else {
        cropped
    }

    val outFile = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { fos ->
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
    }
    return outFile
}

private fun decodeRotatedBitmap(file: File): Bitmap? {
    val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val exif = ExifInterface(file.absolutePath)
    val degrees = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return raw
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

private fun copyUriToTempFileAndDecode(context: Context, uri: Uri): Pair<File?, Bitmap?> {
    val tempFile = File(context.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
    } ?: return null to null
    return tempFile to decodeRotatedBitmap(tempFile)
}
