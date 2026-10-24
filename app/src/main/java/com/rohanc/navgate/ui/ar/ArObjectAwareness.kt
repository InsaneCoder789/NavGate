package com.rohanc.navgate.ui.ar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Where people, vehicles and animals are on the screen, as rectangles in view-normalized coordinates (0..1, origin top left). */
class DetectedBoxes(val rects: FloatArray, val count: Int, val atMillis: Long)

/**
 * Lets the AR road line go behind people, vehicles and animals instead of being painted over them.
 *
 * A small pretrained detector (EfficientDet-Lite0, COCO classes) looks at the camera image about four times a second on its own
 * thread, so the render thread is never held up. The image is resampled straight into screen orientation, so the rectangles it
 * returns need no further mapping. This is awareness of what is in the way for drawing only; it does not warn about obstacles.
 */
class ArObjectAwareness(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ar-objects").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    @Volatile private var detector: ObjectDetector? = null
    @Volatile private var failed = false
    @Volatile var latest = DetectedBoxes(FloatArray(0), 0, 0)
        private set
    private var lastRequestMillis = 0L

    private fun detector(): ObjectDetector? {
        detector?.let { return it }
        if (failed) return null
        return try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE).setMaxResults(8).setScoreThreshold(0.42f)
                .setCategoryAllowlist(listOf("person", "bicycle", "car", "motorcycle", "bus", "truck", "dog", "cow", "horse", "cat"))
                .build()
            ObjectDetector.createFromOptions(context, options).also { detector = it; Log.i("NavGateAR", "Object detector ready") }
        } catch (error: Throwable) { failed = true; Log.w("NavGateAR", "Object detector unavailable: ${error.message}"); null }
    }

    /** Call from the render thread on each camera frame; does the cheap part (copying the image) and hands the rest to the worker. */
    fun maybeDetect(frame: Frame, nowMillis: Long, screenWidth: Int, screenHeight: Int) {
        if (failed || nowMillis - lastRequestMillis < 240 || !busy.compareAndSet(false, true)) return
        lastRequestMillis = nowMillis
        try {
            // Image pixel -> screen position, from ARCore: three points pin down the rotation, scale and crop.
            val input = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
            val output = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, input, Coordinates2d.VIEW_NORMALIZED, output)
            val image = frame.acquireCameraImage()
            val width = image.width; val height = image.height
            val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
            val y = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
            val u = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
            val v = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
            val yRow = yPlane.rowStride; val uvRow = uPlane.rowStride; val uvPixel = uPlane.pixelStride
            image.close()
            executor.execute {
                try { run(y, u, v, width, height, yRow, uvRow, uvPixel, output, screenWidth, screenHeight, nowMillis) }
                catch (error: Throwable) { Log.w("NavGateAR", "Object detection step failed: ${error.message}") }
                finally { busy.set(false) }
            }
        } catch (_: Throwable) { busy.set(false) }   // no image yet, or the frame went away: try again next time
    }

    private fun run(y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int, yRow: Int, uvRow: Int, uvPixel: Int, forward: FloatArray, screenWidth: Int, screenHeight: Int, at: Long) {
        val detector = detector() ?: return
        val outW = 256; val outH = (outW * screenHeight.toFloat() / screenWidth).toInt().coerceIn(256, 640)
        val pixels = YuvResampler.toScreenArgb(y, u, v, width, height, yRow, uvRow, uvPixel, forward, outW, outH) ?: return
        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val rects = FloatArray(32); var count = 0
        for (d in result.detections()) {
            if (count >= 8) break
            val box = d.boundingBox()
            // Grow each box a little: a soft edge looks better than a hard cut, and the box is a quarter-second old.
            val padX = box.width() * 0.08f; val padY = box.height() * 0.06f
            rects[count * 4] = ((box.left - padX) / outW).coerceIn(0f, 1f); rects[count * 4 + 1] = ((box.top - padY) / outH).coerceIn(0f, 1f)
            rects[count * 4 + 2] = ((box.right + padX) / outW).coerceIn(0f, 1f); rects[count * 4 + 3] = ((box.bottom + padY) / outH).coerceIn(0f, 1f)
            count++
        }
        latest = DetectedBoxes(rects, count, at)
        bitmap.recycle()
    }

    fun close() { executor.execute { runCatching { detector?.close() }; detector = null }; executor.shutdown() }
}
