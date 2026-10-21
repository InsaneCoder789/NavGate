package com.rohanc.navgate.ui.ar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
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
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What ARCore reports each frame (kept for the plain-camera fallback overlay). */
class ArFrameState(val reading: ArCameraReading, val focalX: Double, val focalY: Double, val width: Int, val height: Int, val groundHeightMeters: Double?, val tracking: Boolean)

sealed interface ArSessionStatus {
    data object Starting : ArSessionStatus
    data object Running : ArSessionStatus
    data class Unavailable(val reason: String) : ArSessionStatus
}

/**
 * How smooth AR should be.
 *  - Saver: 30 fps camera. Lightest on battery.
 *  - Smooth: 60 fps camera (ARCore's maximum) where the phone supports it.
 *  - Max: 60 fps camera and the road line redrawn at the screen's own refresh rate (up to 120 Hz), with the camera pose predicted between frames.
 *  - Auto: picks one from battery, power saver and heat.
 */
enum class ArFpsMode { Auto, Saver, Smooth, Max }

/** Everything the AR layer needs from the app, replaced as it changes; read on the render thread. */
class ArInputs {
    @Volatile var path: List<GeoPoint> = emptyList()
    @Volatile var roadEndMeters = 0.0
    @Volatile var turnKind: String? = null
    @Volatile var geometry: RouteGeometry? = null
    @Volatile var alongShapeMeters = 0.0
    @Volatile var speedMps = 0.0
    @Volatile var fixElapsedMillis = 0L
    @Volatile var compassAzimuth: Double? = null
    /** True-north heading of the top edge of the phone, used when the camera looks at the ground. */
    @Volatile var compassUpAzimuth: Double? = null
    @Volatile var driving = true
}

/** What the render thread reports back for the on-screen text and status. */
data class ArHud(val turnX: Float?, val turnY: Float?, val tracking: String?, val aligned: Boolean, val groundFound: Boolean, val cameraFps: Int, val overlayFps: Int)

private const val TAG = "NavGateAR"

/** Picks the camera rate and whether to redraw at display rate, from the user's choice and the phone's condition. */
internal fun resolveFps(context: Context, mode: ArFpsMode): Pair<Int, Boolean> {
    if (mode == ArFpsMode.Saver) return 30 to false
    if (mode == ArFpsMode.Smooth) return 60 to false
    if (mode == ArFpsMode.Max) return 60 to true
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) } ?: 100
    val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val hot = Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    return when {
        power.isPowerSaveMode || hot || (!charging && level < 20) -> 30 to false
        charging && !hot -> 60 to true
        else -> 60 to false
    }
}

/**
 * ARCore camera feed with the road line drawn inside the same GL frame as the camera picture, so the line can never lag the image.
 * ARCore fuses the camera with the gyroscope and accelerometer; this layer aligns its world to the real one (compass, then the road's
 * own direction while moving, with the vehicle position locked to the route), measures the phone's height above the ground from
 * plane detection, and draws a ribbon, flowing chevrons and a turn arrow on the road.
 * If ARCore cannot start, [onStatus] reports why so the caller can fall back to the plain camera.
 */
@Composable
fun ArSessionView(inputs: ArInputs, fpsMode: ArFpsMode, onStatus: (ArSessionStatus) -> Unit, onHud: (ArHud) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestStatus by rememberUpdatedState(onStatus)
    val latestHud by rememberUpdatedState(onHud)
    val holder = remember(context) { ArSessionHolder(context, inputs, fpsMode, { latestStatus(it) }, { latestHud(it) }) }
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

private class PoseSample(val timeNanos: Long, val position: DoubleArray, val rotation: DoubleArray)

private class ArSessionHolder(private val context: Context, private val inputs: ArInputs, private val fpsMode: ArFpsMode,
    private val onStatus: (ArSessionStatus) -> Unit, private val onHud: (ArHud) -> Unit) {
    val surface = GLSurfaceView(context)
    private var session: Session? = null
    private val background = BackgroundRenderer()
    private val path = ArPathRenderer()
    private val aligner = ArGeoAligner()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var viewport = intArrayOf(0, 0)
    private val target = resolveFps(context, fpsMode)
    private var groundHeight: Double? = null
    private var groundSamples = 0
    private var planesOff = false
    private var lastTimestamp = 0L
    private var last: PoseSample? = null
    private var previous: PoseSample? = null
    private var lostAtMillis = 0L
    private var cameraFrames = 0; private var drawnFrames = 0; private var windowStart = SystemClock.elapsedRealtime()
    private var cameraFps = 0; private var overlayFps = 0
    private var lastHudAt = 0L
    private val view = FloatArray(16); private val proj = FloatArray(16); private val mvp = FloatArray(16)

    init {
        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(3)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { background.create(); path.create(); session?.setCameraTextureName(background.textureId) }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height); viewport = intArrayOf(width, height)
                session?.setDisplayGeometry(displayRotation(), width, height)
            }
            override fun onDrawFrame(gl: GL10?) = draw()
        })
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        // Max mode asks the system to run the screen at its fastest rate so the overlay can be redrawn that often.
        if (target.second && Build.VERSION.SDK_INT >= 30) surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    @Suppress("DEPRECATION")
    private fun displayRotation() = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    fun resume() {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return
                val created = Session(context)
                created.configure(created.config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    // Max mode redraws faster than the camera delivers, so it must not wait for a new camera frame each time.
                    updateMode = if (target.second) Config.UpdateMode.LATEST_CAMERA_IMAGE else Config.UpdateMode.BLOCKING
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                })
                chooseCameraConfig(created)
                session = created
                created.setCameraTextureName(background.textureId)
                if (viewport[0] > 0) created.setDisplayGeometry(displayRotation(), viewport[0], viewport[1])
                Log.i(TAG, "AR session: camera ${target.first} fps requested, overlay at display rate=${target.second}, mode=$fpsMode")
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

    /** The camera rate asked for, with the cheapest image size that still gives sharp tracking and a full-HD picture. */
    private fun chooseCameraConfig(created: Session) {
        runCatching {
            val wanted = if (target.first >= 60) CameraConfig.TargetFps.TARGET_FPS_60 else CameraConfig.TargetFps.TARGET_FPS_30
            var configs = created.getSupportedCameraConfigs(CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(wanted)))
            if (configs.isEmpty()) configs = created.getSupportedCameraConfigs(CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
            // Smallest tracking image (cheapest) whose picture texture is still at least 1280 wide; fall back to the largest texture.
            val best = configs.filter { it.textureSize.width >= 1280 }.minByOrNull { it.imageSize.width * it.imageSize.height } ?: configs.maxByOrNull { it.textureSize.width }
            best?.let { created.cameraConfig = it; Log.i(TAG, "Camera config: image ${it.imageSize} texture ${it.textureSize} fps ${it.fpsRange}") }
        }
    }

    fun pause() { surface.onPause(); session?.pause() }
    fun release() { surface.onPause(); session?.close(); session = null }

    private fun draw() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val active = session ?: return
        val (width, height) = viewport
        if (width == 0) return
        val frame = try { active.update() } catch (_: Exception) { return }
        background.draw(frame)
        val camera = frame.camera
        val now = SystemClock.elapsedRealtime()
        val tracking = camera.trackingState == TrackingState.TRACKING
        val newImage = frame.timestamp != lastTimestamp
        if (newImage) { lastTimestamp = frame.timestamp; cameraFrames++ }

        if (!tracking) {
            if (lostAtMillis == 0L) lostAtMillis = now
            publishHud(now, null, null, trackingMessage(camera))
            return
        }
        // ARCore's world can shift when tracking comes back after a loss: realign from scratch rather than trust the old offset.
        if (lostAtMillis != 0L) { if (now - lostAtMillis > 1500) { aligner.reset(); last = null; previous = null }; lostAtMillis = 0L }

        val displayPose = camera.displayOrientedPose
        if (newImage || last == null) {
            previous = last
            last = PoseSample(System.nanoTime(), doubleArrayOf(displayPose.tx().toDouble(), displayPose.ty().toDouble(), displayPose.tz().toDouble()),
                doubleArrayOf(displayPose.qx().toDouble(), displayPose.qy().toDouble(), displayPose.qz().toDouble(), displayPose.qw().toDouble()))
            updateGround(active, displayPose)
            alignWorld(displayPose, now)
        }
        // Between camera frames (Max mode) the pose is carried forward at the speed it was last moving, so the line moves at display rate.
        val pose = if (target.second) predictedPose() ?: displayPose else displayPose
        pose.inverse().toMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.1f, 250f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        var turnX: Float? = null; var turnY: Float? = null
        val scene = buildScene(last!!.position[1])
        if (scene != null) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
            val seconds = (now % 100_000L) / 1000f
            path.drawRibbon(mvp, scene.ribbon, seconds, scene.roadEnd)
            scene.arrowOutline?.let { path.drawSolid(mvp, it, 0.04f, 0.07f, 0.12f, 0.92f) }
            scene.arrow?.let { path.drawSolid(mvp, it, if (scene.turnIsArrival) 0.78f else 1.0f, if (scene.turnIsArrival) 1.0f else 0.62f, if (scene.turnIsArrival) 0.0f else 0.0f, 0.97f) }
            scene.turnPoint?.let { t ->
                val clip = FloatArray(4); Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(t.x.toFloat(), scene.groundY + 2.6f, t.z.toFloat(), 1f), 0)
                if (clip[3] > 0.1f) { val nx = clip[0] / clip[3]; val ny = clip[1] / clip[3]; if (nx in -1.2f..1.2f && ny in -1.2f..1.2f) { turnX = (nx + 1f) / 2f; turnY = (1f - ny) / 2f } }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        drawnFrames++
        publishHud(now, turnX, turnY, if (aligner.aligned) null else "Aligning with the road: hold the phone up and look along it")
    }

    // ---- tracking, ground and alignment -------------------------------------------------------------------------------------------------

    private fun updateGround(active: Session, pose: Pose) {
        val y = pose.ty()
        // Height above the ground: the nearest upward-facing horizontal plane that lies well below the phone.
        val floor = active.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && y - it.centerPose.ty() > 0.5f
        }.maxOfOrNull { it.centerPose.ty() }
        if (floor != null) {
            groundSamples++
            val measured = (y - floor).toDouble().coerceIn(0.7, 2.5)
            groundHeight = groundHeight?.let { it + (measured - it) * 0.06 } ?: measured
        }
        // Once the ground height has settled, stop plane detection: it is the most expensive part of tracking.
        if (!planesOff && groundSamples > 120) { planesOff = true; runCatching { active.configure(active.config.apply { planeFindingMode = Config.PlaneFindingMode.DISABLED }) } }
    }

    /** Feeds ARCore's camera and the route-locked vehicle position to the aligner. The road's own direction aligns the world once moving. */
    private fun alignWorld(pose: Pose, now: Long) {
        val geometry = inputs.geometry ?: return
        val alongNow = (inputs.alongShapeMeters + inputs.speedMps * ((now - inputs.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)).coerceIn(0.0, geometry.length)
        val here = geometry.pointAt(alongNow); val bearing = geometry.bearingAt(alongNow)
        fun axis(i: Int) = pose.getTransformedAxis(i, 1f).let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
        val z = axis(2)
        val reading = ArCameraReading(doubleArrayOf(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()), doubleArrayOf(-z[0], -z[1], -z[2]), axis(0), axis(1), now)
        aligner.update(reading, inputs.compassAzimuth, here, 3.0, bearing, inputs.speedMps, inputs.compassUpAzimuth)
    }

    private fun predictedPose(): Pose? {
        val l = last ?: return null; val p = previous ?: return null
        val span = (l.timeNanos - p.timeNanos).toDouble()
        if (span <= 0 || span > 200e6) return null
        val k = ((System.nanoTime() - l.timeNanos) / span).coerceIn(0.0, 1.6)
        val position = DoubleArray(3) { l.position[it] + (l.position[it] - p.position[it]) * k }
        val q = ArMath.extrapolate(p.rotation, l.rotation, 1.0 + k)
        return Pose(floatArrayOf(position[0].toFloat(), position[1].toFloat(), position[2].toFloat()), floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))
    }

    // ---- what to draw -------------------------------------------------------------------------------------------------------------------

    private class Scene(val ribbon: ArMeshData, val arrow: ArMeshData?, val arrowOutline: ArMeshData?, val turnPoint: GroundPoint?, val turnIsArrival: Boolean, val roadEnd: Float, val groundY: Float)

    private fun buildScene(cameraY: Double): Scene? {
        val geo = inputs.path
        if (geo.size < 2 || !aligner.aligned) return null
        val ground = geo.mapNotNull { aligner.toGround(it) }
        if (ground.size < 2) return null
        val eye = groundHeight ?: if (inputs.driving) 1.25 else 1.45
        val y = cameraY - eye + 0.02
        val ribbon = ArMesh.ribbon(ground, y, 0.8)
        val end = inputs.roadEndMeters
        var turn: GroundPoint? = null; var arrow: ArMeshData? = null; var outline: ArMeshData? = null
        val arrival = inputs.turnKind == "Arrive"
        // Walk the path to the end of this road and read the direction in and out of it.
        var travelled = 0.0; var index = ground.lastIndex
        for (i in 1 until ground.size) { travelled += hypot(ground[i].x - ground[i - 1].x, ground[i].z - ground[i - 1].z); if (travelled >= end) { index = i; break } }
        if (inputs.turnKind != null && index >= 2) {
            turn = ground[index]
            fun unit(a: GroundPoint, b: GroundPoint): GroundPoint { val dx = b.x - a.x; val dz = b.z - a.z; val n = hypot(dx, dz).takeIf { it > 1e-6 } ?: 1.0; return GroundPoint(dx / n, dz / n) }
            val incoming = unit(ground[max(0, index - 3)], ground[index])
            val outgoing = if (index < ground.lastIndex) unit(ground[index], ground[min(ground.lastIndex, index + 3)]) else incoming
            if (arrival) { arrow = ArMesh.ring(turn, y + 0.03, 2.2, 0.55); outline = ArMesh.ring(turn, y + 0.025, 2.2, 1.0) }
            else { arrow = ArMesh.turnArrow(turn, incoming, outgoing, y + 0.03); outline = ArMesh.turnArrow(turn, incoming, outgoing, y + 0.025, 1.6) }
        }
        return Scene(ribbon, arrow, outline, turn, arrival, end.toFloat(), y.toFloat())
    }

    private fun trackingMessage(camera: Camera): String? = when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark for AR: turn on more light or use the map"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast: hold the phone steadier"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point the camera at the road and surroundings, not a blank surface"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is in use by another app"
        else -> "Getting your bearings: move the phone slowly"
    }

    private fun publishHud(now: Long, turnX: Float?, turnY: Float?, tracking: String?) {
        if (now - windowStart >= 1000) { cameraFps = cameraFrames; overlayFps = drawnFrames; cameraFrames = 0; drawnFrames = 0; windowStart = now }
        if (now - lastHudAt < 60) return
        lastHudAt = now
        val hud = ArHud(turnX, turnY, tracking, aligner.aligned, groundHeight != null, cameraFps, overlayFps)
        main.post { onHud(hud) }
    }
}

/** Draws the ribbon and arrow meshes with a small shader: fade with distance, glowing edges, chevrons that flow toward the turn. */
private class ArPathRenderer {
    private var program = 0; private var buffer = 0
    private var aPos = 0; private var aUv = 0; private var uMvp = 0; private var uMode = 0; private var uTime = 0; private var uEnd = 0; private var uColor = 0
    private var staging: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 6 * 400).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;
            void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }""")
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float; varying vec2 vUv; uniform int uMode; uniform float uTime; uniform float uEnd; uniform vec4 uColor;
            void main(){
              if (uMode == 1) { gl_FragColor = uColor; return; }
              float side = abs(vUv.x);
              float edge = smoothstep(0.80, 0.96, side);
              // Chevrons point along the road and flow toward the turn.
              float phase = fract((vUv.y - uTime * 4.5) / 5.0 + side * 0.18);
              float chev = smoothstep(0.0, 0.05, phase) * (1.0 - smoothstep(0.09, 0.17, phase));
              vec3 body = mix(vec3(0.0, 0.90, 1.0), vec3(0.78, 1.0, 0.0), edge);
              vec3 col = mix(body, vec3(1.0), chev * 0.9 * (1.0 - edge));
              float far = 1.0 - smoothstep(uEnd * 0.55, uEnd + 6.0, vUv.y);
              float beyond = vUv.y > uEnd ? 0.35 : 1.0;
              float near = smoothstep(0.8, 3.5, vUv.y);
              float a = (0.58 + 0.30 * (1.0 - side)) * far * beyond * near;
              a = max(a, edge * far * beyond * near * 0.95);
              gl_FragColor = vec4(col, a);
            }""")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "AR path shader link failed: ${GLES20.glGetProgramInfoLog(program)}")
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp"); uMode = GLES20.glGetUniformLocation(program, "uMode")
        uTime = GLES20.glGetUniformLocation(program, "uTime"); uEnd = GLES20.glGetUniformLocation(program, "uEnd"); uColor = GLES20.glGetUniformLocation(program, "uColor")
        val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); buffer = ids[0]
    }

    fun drawRibbon(mvp: FloatArray, mesh: ArMeshData, seconds: Float, roadEnd: Float) {
        GLES20.glUseProgram(program); GLES20.glUniform1i(uMode, 0); GLES20.glUniform1f(uTime, seconds); GLES20.glUniform1f(uEnd, max(roadEnd, 10f))
        draw(mvp, mesh)
    }

    fun drawSolid(mvp: FloatArray, mesh: ArMeshData, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(program); GLES20.glUniform1i(uMode, 1); GLES20.glUniform4f(uColor, r, g, b, a)
        draw(mvp, mesh)
    }

    private fun draw(mvp: FloatArray, mesh: ArMeshData) {
        if (mesh.vertexCount == 0) return
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        if (staging.capacity() < mesh.vertices.size) staging = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        staging.clear(); staging.put(mesh.vertices, 0, mesh.vertexCount * 5); staging.flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertexCount * 5 * 4, staging, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, 0); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
        val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "AR shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
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
