// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.wangzan101.mika.R

internal sealed interface CameraCode {
    data class Transfer(val code: String) : CameraCode
    data class Other(val text: String) : CameraCode
    companion object {
        fun parse(text: String): CameraCode = if (text.startsWith("tadolio-transfer:"))
            Transfer(text.removePrefix("tadolio-transfer:")) else Other(text)
    }
}

/** Reads a QR code from a picked image; large photos are sampled down first so decoding stays fast. */
internal fun decodeQrImage(context: Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    return runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))), hints).text
    }.getOrNull()
}

@Composable
internal fun TadolioCamera(back: () -> Unit, manual: () -> Unit, transfer: (String) -> Unit,
    photo: (String) -> Unit, message: (String) -> Unit, showManual: Boolean = true) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf<String?>(null) }
    var capture by remember { mutableStateOf<CameraPreview?>(null) }
    var taking by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    var noQr by remember { mutableStateOf(false) }
    val currentTransfer by rememberUpdatedState(transfer)
    val currentPhoto by rememberUpdatedState(photo)
    val found: (String) -> Unit = { value ->
        if (!delivered && !taking) when (val result = CameraCode.parse(value)) {
            is CameraCode.Transfer -> { delivered = true; currentTransfer(result.code) }
            is CameraCode.Other -> { other = result.text; noQr = false }
        }
    }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching { decodeQrImage(context, uri) }.getOrNull()
            if (text == null) noQr = true else found(text)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    LifecycleResumeEffect(Unit) {
        active = true
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { active = false }
    }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = back) { Text(stringResource(R.string.number_back)) }
            if (!granted || failed) {
                Text(stringResource(if (!granted) R.string.t42_camera_permission else R.string.t42_camera_failed))
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) {
                    Text(stringResource(R.string.t42_camera_settings))
                }
            } else {
                other?.let { text -> TextButton(enabled = !taking && !delivered, onClick = { delivered = true; message(text) }) { Text(stringResource(R.string.t42_qr_found)) } }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    if (active) AndroidView(factory = { ctx ->
                        TextureView(ctx).also { view ->
                            capture = CameraPreview(ctx, view, found,
                                { uri -> if (!delivered) { delivered = true; currentPhoto(uri) } }, { taking = false; failed = true })
                        }
                    }, modifier = Modifier.fillMaxSize(), onRelease = { capture?.close(); capture = null })
                }
                Button(enabled = capture?.ready == true && !taking && !delivered, modifier = Modifier.align(Alignment.CenterHorizontally), onClick = { taking = true; capture?.take() }) {
                    Text(stringResource(R.string.t42_shutter))
                }
            }
            if (noQr) Text(stringResource(R.string.t42_no_qr))
            TextButton(enabled = !taking && !delivered, onClick = { album.launch("image/*") }) { Text(stringResource(R.string.t42_album_qr)) }
            if (showManual) TextButton(onClick = manual) { Text(stringResource(R.string.t42_manual)) }
        }
    }
}

/** One Camera2 session supplies the preview, ZXing luma frames and full-size JPEG capture. */
@Suppress("MissingPermission", "DEPRECATION")
private class CameraPreview(private val context: Context, private val view: TextureView,
    private val code: (String) -> Unit, private val photo: (String) -> Unit, private val error: () -> Unit) : AutoCloseable {
    private val thread = HandlerThread("tadolio-camera").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpeg: ImageReader? = null
    private var frames: ImageReader? = null
    private var preview: Surface? = null
    @Volatile private var closed = false
    @Volatile private var pending = false
    @Volatile private var upright = android.util.Size(1080, 1920)
    private var orientation = 0
    var ready by mutableStateOf(false)
        private set
    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { worker.post { open(texture) } }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { close(); return true }
        }
    }
    private fun fail() { if (!closed) main.post { error() }; close() }
    private fun open(texture: SurfaceTexture) {
        if (closed) return
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val info = manager.getCameraCharacteristics(id)
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            // Full-sensor JPEG (e.g. 50 MP remosaic) cannot run alongside preview + YUV on many phones and the
            // capture silently never arrives. About 12 MP is plenty for reading text in a photo.
            val jpegSizes = sizes.getOutputSizes(ImageFormat.JPEG)
            val full = jpegSizes.filter { it.width.toLong() * it.height <= 12_600_000L }.maxByOrNull { it.width.toLong() * it.height }
                ?: jpegSizes.minBy { it.width.toLong() * it.height }
            val scan = sizes.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 640 }.maxByOrNull { it.width * it.height }
                ?: sizes.getOutputSizes(ImageFormat.YUV_420_888).minBy { it.width * it.height }
            val screenRotation = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            orientation = ((info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - screenRotation + 360) % 360
            val previewSize = sizes.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height } ?: scan
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val sensorSideways = (info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0
            val displaySideways = screenRotation % 180 != 0
            // Upright size of what the preview shows; also used for the shutter fallback bitmap.
            upright = if (sensorSideways != displaySideways) android.util.Size(previewSize.height, previewSize.width) else previewSize
            main.post {
                if (!closed && view.width > 0 && view.height > 0) {
                    val vw = view.width.toFloat(); val vh = view.height.toFloat()
                    val transform = android.graphics.Matrix()
                    if (displaySideways) {
                        // Landscape display: SurfaceTexture does not rotate for us (Camera2Basic recipe).
                        val viewRect = android.graphics.RectF(0f, 0f, vw, vh)
                        val bufferRect = android.graphics.RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
                        bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY())
                        transform.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
                        val scale = maxOf(vh / previewSize.height, vw / previewSize.width)
                        transform.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
                        transform.postRotate(90f * (screenRotation / 90 - 2), viewRect.centerX(), viewRect.centerY())
                    } else {
                        // Portrait: the SurfaceTexture already turns the sensor image upright and stretches it to
                        // the view. Only undo the stretch, cropping the overflow (center-crop, no extra zoom).
                        val content = upright.width.toFloat() / upright.height
                        val viewRatio = vw / vh
                        if (viewRatio > content) transform.setScale(1f, viewRatio / content, vw / 2, vh / 2)
                        else transform.setScale(content / viewRatio, 1f, vw / 2, vh / 2)
                        if (screenRotation == 180) transform.postRotate(180f, vw / 2, vh / 2)
                    }
                    view.setTransform(transform)
                }
            }
            preview = Surface(texture)
            jpeg = ImageReader.newInstance(full.width, full.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.use { image ->
                        if (!pending) return@use
                        pending = false
                        val buffer = image.planes[0].buffer
                        save(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    }
                }, worker)
            }
            var lastScan = 0L
            frames = ImageReader.newInstance(scan.width, scan.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.use { image ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastScan < 350 || closed) return@use
                        lastScan = now
                        val plane = image.planes[0]
                        val data = ByteArray(image.width * image.height)
                        for (y in 0 until image.height) for (x in 0 until image.width) data[y * image.width + x] = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride)
                        val source = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0, image.width, image.height, false)
                        val result = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text }.getOrNull()
                        if (result != null) main.post { if (!closed) code(result) }
                    }
                }, worker)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    camera.createCaptureSession(listOf(preview!!, frames!!.surface, jpeg!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (closed) { value.close(); return }
                            session = value
                            try {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(preview!!); addTarget(frames!!.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                value.setRepeatingRequest(request.build(), null, worker)
                                main.post { if (!closed) ready = true }
                            } catch (_: Exception) { fail() }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fail() }
                    }, worker)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail() }
                override fun onError(camera: CameraDevice, reason: Int) { camera.close(); fail() }
            }, worker)
        } catch (_: Exception) { fail() }
    }
    private fun save(bytes: ByteArray) {
        try {
            val uri = AttachFiles.newCameraUri(context)
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            main.post { if (!closed) photo(uri.toString()) }
        } catch (_: Exception) { fail() }
    }
    /**
     * If the still capture fails or is slow, keep the shutter working by saving the preview frame. Some HALs
     * (Samsung Exynos) take a minute for a still and block the camera thread meanwhile, so the timer runs on
     * the main thread and encoding runs on its own thread, never on [worker].
     */
    private fun fallback() { main.post {
        if (closed || !pending) return@post
        pending = false
        val bitmap = runCatching { view.getBitmap(upright.width, upright.height) }.getOrNull() ?: return@post fail()
        Thread {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            save(out.toByteArray())
        }.start()
    } }
    fun take() {
        pending = true
        main.postDelayed({ fallback() }, 2500)
        worker.post {
            try {
                val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg!!.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, orientation)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                session!!.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { fallback() }
                }, worker)
            } catch (_: Exception) { fallback() }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        main.post { ready = false }
        worker.post { session?.close(); device?.close(); frames?.close(); jpeg?.close(); preview?.release(); thread.quitSafely() }
    }
}

@Composable
internal fun CameraFlowScreen(back: () -> Unit, photo: (String) -> Unit, message: (String) -> Unit, showManual: Boolean = true) {
    var receive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var code by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var completed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (completed) {
        NumberScreen(back)
    } else if (receive) {
        NumberMoveScreen(initialCode = code, onTransferred = { completed = true }) { receive = false; code = "" }
    } else TadolioCamera(back, manual = { receive = true }, transfer = { code = it; receive = true }, photo, message, showManual)
}
