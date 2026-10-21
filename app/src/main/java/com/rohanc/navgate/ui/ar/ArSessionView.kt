package com.rohanc.navgate.ui.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What ARCore reports each frame. [focalX]/[focalY] are screen-space focal lengths from the real camera, so the overlay lines up with the picture. */
class ArFrameState(val reading: ArCameraReading, val focalX: Double, val focalY: Double, val width: Int, val height: Int, val groundHeightMeters: Double?, val tracking: Boolean)

sealed interface ArSessionStatus {
    data object Starting : ArSessionStatus
    data object Running : ArSessionStatus
    data class Unavailable(val reason: String) : ArSessionStatus
}

/**
 * ARCore camera feed with motion tracking. ARCore fuses the camera with the gyroscope and accelerometer, which is what keeps
 * the road line steady; horizontal-plane detection measures the real height of the phone above the ground.
 * If ARCore cannot start, [onStatus] reports why so the caller can fall back to the plain camera.
 */
@Composable
fun ArSessionView(onFrame: (ArFrameState) -> Unit, onStatus: (ArSessionStatus) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestFrame by rememberUpdatedState(onFrame)
    val latestStatus by rememberUpdatedState(onStatus)
    val holder = remember(context) { ArSessionHolder(context, { latestFrame(it) }, { latestStatus(it) }) }
    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.resume()
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.resume()
        onDispose { lifecycle.removeObserver(observer); holder.release() }
    }
    AndroidView(factory = { holder.surface }, modifier = modifier)
}

private class ArSessionHolder(private val context: Context, private val onFrame: (ArFrameState) -> Unit, private val onStatus: (ArSessionStatus) -> Unit) {
    val surface = GLSurfaceView(context)
    private var session: Session? = null
    private val renderer = BackgroundRenderer()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var viewport = intArrayOf(0, 0)
    private var groundHeight: Double? = null
    private var wasTracking = false
    private var groundSamples = 0
    private var planesOff = false

    init {
        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(3)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { renderer.create(); session?.setCameraTextureName(renderer.textureId) }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height); viewport = intArrayOf(width, height)
                @Suppress("DEPRECATION")
                val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                session?.setDisplayGeometry(rotation, width, height)
            }
            override fun onDrawFrame(gl: GL10?) = draw()
        })
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun resume() {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return
                val created = Session(context)
                created.configure(created.config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode = Config.UpdateMode.BLOCKING   // frames arrive at the camera rate; no busy loop
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                })
                // Battery: 30 fps is plenty for a line on a road; the default 60 fps roughly doubles camera and tracking work.
                runCatching {
                    val filter = CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                    created.getSupportedCameraConfigs(filter).firstOrNull()?.let { created.cameraConfig = it }
                }
                session = created
                created.setCameraTextureName(renderer.textureId)
                if (viewport[0] > 0) {
                    @Suppress("DEPRECATION")
                    created.setDisplayGeometry((context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation, viewport[0], viewport[1])
                }
            } catch (error: Exception) {
                main.post { onStatus(ArSessionStatus.Unavailable(when (error) {
                    is UnavailableDeviceNotCompatibleException -> "This phone does not support ARCore."
                    is UnavailableUserDeclinedInstallationException, is UnavailableArcoreNotInstalledException -> "ARCore (Google Play Services for AR) is needed for AR navigation."
                    is CameraNotAvailableException -> "The camera is in use by another app."
                    else -> "AR could not start: ${error.message ?: error.javaClass.simpleName}"
                })) }
                return
            }
        }
        try { session?.resume(); surface.onResume(); main.post { onStatus(ArSessionStatus.Starting) } }
        catch (_: CameraNotAvailableException) { main.post { onStatus(ArSessionStatus.Unavailable("The camera is in use by another app.")) } }
    }

    fun pause() { surface.onPause(); session?.pause() }
    fun release() { surface.onPause(); session?.close(); session = null }

    private fun draw() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val active = session ?: return
        val (width, height) = viewport
        if (width == 0) return
        val frame = try { active.update() } catch (_: Exception) { return }
        renderer.draw(frame)
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (!tracking) {
            if (wasTracking) { wasTracking = false }
        } else {
            val pose = camera.displayOrientedPose
            fun axis(i: Int) = pose.getTransformedAxis(i, 1f).let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
            val z = axis(2)
            val t = pose.translation
            // Height above the ground: the nearest upward-facing horizontal plane that lies well below the phone.
            val floor = active.getAllTrackables(Plane::class.java).filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t[1] - it.centerPose.ty() > 0.6f
            }.maxOfOrNull { it.centerPose.ty() }
            if (floor != null) {
                groundSamples++
                val measured = (t[1] - floor).toDouble().coerceIn(0.8, 2.4)
                groundHeight = groundHeight?.let { it + (measured - it) * 0.05 } ?: measured
            }
            // Once the ground height has settled, stop plane detection: it is the most expensive part of tracking and no longer needed.
            if (!planesOff && groundSamples > 90) {
                planesOff = true
                runCatching { active.configure(active.config.apply { planeFindingMode = Config.PlaneFindingMode.DISABLED }) }
            }
            val matrix = FloatArray(16).also { camera.getProjectionMatrix(it, 0, 0.1f, 200f) }
            val state = ArFrameState(
                ArCameraReading(doubleArrayOf(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()), doubleArrayOf(-z[0], -z[1], -z[2]), axis(0), axis(1), System.currentTimeMillis()),
                matrix[0] * width / 2.0, matrix[5] * height / 2.0, width, height, groundHeight, true)
            wasTracking = true
            main.post { onFrame(state) }
        }
    }
}

/** Draws the camera image ARCore delivers as a full-screen quad. */
private class BackgroundRenderer {
    var textureId = 0
    private var program = 0
    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).let { ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it).also { b -> b.position(0) } }
    private val texture: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val vertex = compile(GLES20.GL_VERTEX_SHADER, "attribute vec4 a;attribute vec2 t;varying vec2 v;void main(){gl_Position=a;v=t;}")
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 v;uniform samplerExternalOES s;void main(){gl_FragColor=texture2D(s,v);}")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) { texture.position(0); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, texture) }
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        val a = GLES20.glGetAttribLocation(program, "a"); val t = GLES20.glGetAttribLocation(program, "t")
        quad.position(0); texture.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, texture)
        GLES20.glEnableVertexAttribArray(a); GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(t)
        GLES20.glDepthMask(true)
    }

    private fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, source); GLES20.glCompileShader(it) }
}
