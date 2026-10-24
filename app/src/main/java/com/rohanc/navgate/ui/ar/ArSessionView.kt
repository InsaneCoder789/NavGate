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
    /** Hide the line behind people, vehicles and animals the camera sees. */
    @Volatile var objectAwareness = true
}

/** What the render thread reports back for the on-screen text and status. */
data class ArHud(val turnX: Float?, val turnY: Float?, val tracking: String?, val aligned: Boolean, val groundFound: Boolean, val cameraFps: Int, val overlayFps: Int, val objects: Int = 0, val phoneHeight: Double = 0.0, val confidence: Double = 0.0)

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
fun ArSessionView(inputs: ArInputs, fpsMode: ArFpsMode, onStatus: (ArSessionStatus) -> Unit, onHud: (ArHud) -> Unit, modifier: Modifier = Modifier, domeTop: Float = 0f) {
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
    AndroidView(factory = {
        holder.surface.also { v ->
            if (domeTop > 0f && android.os.Build.VERSION.SDK_INT >= 30) {
                // The picture keeps only the part above a round bite, so the live map shows through the lower part of the screen.
                v.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        val w = view.width.toFloat(); val h = view.height.toFloat(); val top = h * domeTop; val ry = (h - top) * 1.12f
                        val path = android.graphics.Path().apply {
                            fillType = android.graphics.Path.FillType.EVEN_ODD
                            addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
                            addOval(-w * 0.5f, top, w * 1.5f, top + 2 * ry, android.graphics.Path.Direction.CW)
                        }
                        outline.setPath(path)
                    }
                }
                v.clipToOutline = true
            }
        }
    }, modifier = modifier)
}

private class PoseSample(val timeNanos: Long, val position: DoubleArray, val rotation: DoubleArray)

private class ArSessionHolder(private val context: Context, private val inputs: ArInputs, private val fpsMode: ArFpsMode,
    private val onStatus: (ArSessionStatus) -> Unit, private val onHud: (ArHud) -> Unit) {
    val surface = GLSurfaceView(context)
    private var session: Session? = null
    private val background = BackgroundRenderer()
    private val path = ArPathRenderer()
    private val objects = ArObjectAwareness(context)
    private val world = ArWorld()
    private val aligner get() = world.aligner
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var viewport = intArrayOf(0, 0)
    private val target = resolveFps(context, fpsMode)
    private val ground = GroundEstimator()
    private var groundSamples = 0
    private var phoneHeightNow = 0.0
    private var planesOff = false
    private var lastTimestamp = 0L
    private var last: PoseSample? = null
    private var previous: PoseSample? = null
    private var lastAxes: Pair<DoubleArray, DoubleArray>? = null
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
                    depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
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
    fun release() { surface.onPause(); session?.close(); session = null; objects.close() }

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
        // Looks at the picture for people and vehicles whether or not tracking has started, a few times a second.
        if (newImage && inputs.objectAwareness && fpsPolicyAllowsObjects()) objects.maybeDetect(frame, now, width, height)

        if (!tracking) {
            if (lostAtMillis == 0L) lostAtMillis = now
            publishHud(now, null, null, trackingMessage(camera))
            return
        }
        // ARCore's world can shift when tracking comes back after a loss: realign from scratch rather than trust the old offset.
        if (lostAtMillis != 0L) { if (now - lostAtMillis > 1500) { world.relocalized(); ground.reset(); groundSamples = 0; last = null; previous = null }; lostAtMillis = 0L }

        val displayPose = camera.displayOrientedPose
        if (newImage || last == null) {
            previous = last
            last = PoseSample(System.nanoTime(), doubleArrayOf(displayPose.tx().toDouble(), displayPose.ty().toDouble(), displayPose.tz().toDouble()),
                doubleArrayOf(displayPose.qx().toDouble(), displayPose.qy().toDouble(), displayPose.qz().toDouble(), displayPose.qw().toDouble()))
            updateGround(frame, active, displayPose, width, height)
            alignWorld(displayPose, now)
        }
        // Between camera frames (Max mode) the pose is carried forward at the speed it was last moving, so the line moves at display rate.
        val pose = if (target.second) predictedPose() ?: displayPose else displayPose
        pose.inverse().toMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.1f, 250f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        var turnX: Float? = null; var turnY: Float? = null
        val scene = buildScene(last!!.position[1], GroundPoint(last!!.position[0], last!!.position[2]))
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        drawFeetMarker(now)
        if (scene != null) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
            val seconds = (now % 100_000L) / 1000f
            path.setHidden(if (inputs.objectAwareness && fpsPolicyAllowsObjects()) objects.latest.takeIf { now - it.atMillis < 1200 } else null, width, height)
            path.drawRibbon(mvp, scene.ribbon, seconds, scene.roadEnd, world.confidence.toFloat())
            scene.arrowOutline?.let { path.drawSolid(mvp, it, 0.04f, 0.07f, 0.12f, 0.92f) }
            scene.arrow?.let { path.drawSolid(mvp, it, if (scene.turnIsArrival) 1.0f else 0.66f, if (scene.turnIsArrival) 1.0f else 0.80f, 1.0f, 0.97f) }
            scene.turnPoint?.let { t ->
                val clip = FloatArray(4); Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(t.x.toFloat(), scene.groundY + 2.6f, t.z.toFloat(), 1f), 0)
                if (clip[3] > 0.1f) { val nx = clip[0] / clip[3]; val ny = clip[1] / clip[3]; if (nx in -1.2f..1.2f && ny in -1.2f..1.2f) { turnX = (nx + 1f) / 2f; turnY = (1f - ny) / 2f } }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        drawnFrames++
        publishHud(now, turnX, turnY, when { !aligner.aligned -> "Aligning with the road: hold the phone up and look along it"; world.confidence < 0.5 -> "Lining up with the road: walk a few steps forward"; else -> null })
    }

    /**
     * "You are here": a small blue disc and ring on the ground under the body, only while the phone looks down at the feet, and held to the
     * ground itself, so lifting or lowering the phone never drags it. It fades in as the phone tilts from level toward straight down.
     */
    private fun drawFeetMarker(now: Long) {
        val l = last ?: return
        val axes = lastAxes ?: return
        val plan = FeetMarker.plan(l.position[0], l.position[2], axes.first, axes.second) ?: return
        val y = ground.groundY(l.position[1], assumedPhoneHeight()) + 0.015
        val centre = GroundPoint(plan.x, plan.z)
        val phase = ((now % 1800L) / 1800.0)
        val a = plan.alpha
        path.drawSolid(mvp, ArMesh.disc(centre, y, plan.radius), 0.20f, 0.50f, 1.0f, 0.26f * a)
        path.drawSolid(mvp, ArMesh.ring(centre, y + 0.004, plan.radius, 0.07), 0.30f, 0.60f, 1.0f, 0.95f * a)
        path.drawSolid(mvp, ArMesh.ring(centre, y + 0.002, plan.radius + 0.4 * phase, 0.05), 0.30f, 0.60f, 1.0f, (0.5 * (1.0 - phase)).toFloat() * a)
    }

    /** A phone is held lower in a car mount than at a walker's chest; this only matters until the ground has been measured. */
    private fun assumedPhoneHeight() = if (inputs.driving) 1.25 else 1.3

    // ---- tracking, ground and alignment -------------------------------------------------------------------------------------------------

    /**
     * Measures the ground: rays from the lower part of the picture are tested against the planes ARCore has found, and the height where
     * they land is the ground's height in the world. Phone height above the ground then follows from where the phone is, so it is always current.
     */
    private fun updateGround(frame: Frame, active: Session, pose: Pose, width: Int, height: Int) {
        val camY = pose.ty().toDouble()
        var hits = 0
        for ((fx, fy) in listOf(0.5f to 0.88f, 0.5f to 0.7f, 0.3f to 0.8f, 0.7f to 0.8f, 0.5f to 0.5f)) {
            val hit = frame.hitTest(width * fx, height * fy).firstOrNull { r ->
                val t = r.trackable
                (t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(r.hitPose)) || t is DepthPoint
            } ?: continue
            ground.addSample(hit.hitPose.ty().toDouble(), camY); hits++
        }
        if (hits > 0) groundSamples++
        phoneHeightNow = ground.phoneHeight(camY, assumedPhoneHeight())
        // Once the ground has settled, stop plane detection: it is the most expensive part of tracking. The height is kept as a world value.
        if (!planesOff && ground.found && groundSamples > 120) { planesOff = true; runCatching { active.configure(active.config.apply { planeFindingMode = Config.PlaneFindingMode.DISABLED }) } }
    }

    /** Feeds ARCore's camera and the route-locked vehicle position to the AR engine. The road's own direction aligns the world once moving. */
    private fun alignWorld(pose: Pose, now: Long) {
        val geometry = inputs.geometry ?: return
        val alongNow = inputs.alongShapeMeters + inputs.speedMps * ((now - inputs.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
        fun axis(i: Int) = pose.getTransformedAxis(i, 1f).let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
        val z = axis(2)
        val reading = ArCameraReading(doubleArrayOf(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()), doubleArrayOf(-z[0], -z[1], -z[2]), axis(0), axis(1), now)
        lastAxes = reading.forward to reading.up
        world.align(reading, inputs.compassAzimuth, inputs.compassUpAzimuth, geometry, alongNow, inputs.speedMps)
    }

    private fun predictedPose(): Pose? {
        val l = last ?: return null; val p = previous ?: return null
        val span = (l.timeNanos - p.timeNanos).toDouble()
        if (span <= 0 || span > 200e6) return null
        val (position, q) = PosePredictor.predict(p.position, p.rotation, l.position, l.rotation, (System.nanoTime() - l.timeNanos) / span)
        return Pose(floatArrayOf(position[0].toFloat(), position[1].toFloat(), position[2].toFloat()), floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))
    }

    // ---- what to draw -------------------------------------------------------------------------------------------------------------------

    private fun buildScene(cameraY: Double, cameraGround: GroundPoint): ArScene? =
        world.buildScene(inputs.path, inputs.roadEndMeters, inputs.turnKind, ground.groundY(cameraY, assumedPhoneHeight()), cameraGround)

    /** Object awareness costs battery, so the 30 fps saver mode leaves it off. */
    private fun fpsPolicyAllowsObjects() = target.first >= 60 || fpsMode != ArFpsMode.Saver

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
        val hud = ArHud(turnX, turnY, tracking, aligner.aligned, ground.found, cameraFps, overlayFps, objects.latest.takeIf { now - it.atMillis < 1200 }?.count ?: 0, phoneHeightNow, world.confidence)
        ArDiagnostics.record(now, hud)
        main.post { onHud(hud) }
    }
}

/** Draws the ribbon and arrow meshes with a small shader: fade with distance, glowing edges, chevrons that flow toward the turn. */
private class ArPathRenderer {
    private var program = 0; private var buffer = 0
    private var aPos = 0; private var aUv = 0; private var uMvp = 0; private var uMode = 0; private var uTime = 0; private var uEnd = 0; private var uColor = 0; private var uBox = 0; private var uViewport = 0; private var uConfidence = 0
    private val boxes = FloatArray(32); private var viewW = 1f; private var viewH = 1f
    private var staging: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 6 * 400).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;
            void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }""")
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float; varying vec2 vUv; uniform int uMode; uniform float uTime; uniform float uEnd; uniform vec4 uColor;
            uniform vec4 uBox[8]; uniform vec2 uViewport; uniform float uConfidence;
            // 0 where something stands in front of the road (the lower part of a person's or vehicle's box), 1 elsewhere, with a soft edge.
            float clearOfObjects() {
              vec2 p = vec2(gl_FragCoord.x / uViewport.x, 1.0 - gl_FragCoord.y / uViewport.y);
              float keep = 1.0;
              for (int i = 0; i < 8; i++) {
                vec4 b = uBox[i];
                if (b.z > b.x) {
                  float top = b.y + (b.w - b.y) * 0.18;
                  float inX = smoothstep(b.x - 0.01, b.x + 0.015, p.x) * (1.0 - smoothstep(b.z - 0.015, b.z + 0.01, p.x));
                  float inY = smoothstep(top - 0.01, top + 0.02, p.y) * (1.0 - smoothstep(b.w - 0.01, b.w + 0.02, p.y));
                  keep *= 1.0 - inX * inY;
                }
              }
              return keep;
            }
            void main(){
              float visible = clearOfObjects();
              if (uMode == 1) { gl_FragColor = vec4(uColor.rgb, uColor.a * visible); return; }
              float side = abs(vUv.x);
              // The leading indicator: a solid blue band with bright rails and bold white chevrons that run toward the turn.
              float rail = smoothstep(0.80, 0.88, side) * (1.0 - smoothstep(0.95, 1.0, side));
              float phase = fract((vUv.y - uTime * 3.0) / 2.0 + side * 0.30);
              float chev = smoothstep(0.0, 0.04, phase) * (1.0 - smoothstep(0.17, 0.30, phase));
              vec3 blue = vec3(0.17, 0.50, 1.0); vec3 ice = vec3(0.66, 0.80, 1.0);
              vec3 col = mix(mix(blue, ice, side * 0.35), vec3(1.0), chev * 0.92);
              col = mix(col, ice, rail * 0.9);
              float far = 1.0 - smoothstep(uEnd * 0.6, uEnd + 6.0, vUv.y);
              float beyond = vUv.y > uEnd ? 0.4 : 1.0;
              float near = smoothstep(0.5, 2.4, vUv.y);
              float a = (0.80 + 0.15 * chev) * far * beyond * near;
              // Less sure of the alignment: fainter line, never a confident-looking one that may be wrong.
              gl_FragColor = vec4(col, a * visible * mix(0.45, 1.0, uConfidence));
            }""")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "AR path shader link failed: ${GLES20.glGetProgramInfoLog(program)}")
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp"); uMode = GLES20.glGetUniformLocation(program, "uMode")
        uTime = GLES20.glGetUniformLocation(program, "uTime"); uEnd = GLES20.glGetUniformLocation(program, "uEnd"); uColor = GLES20.glGetUniformLocation(program, "uColor")
        uBox = GLES20.glGetUniformLocation(program, "uBox"); uViewport = GLES20.glGetUniformLocation(program, "uViewport"); uConfidence = GLES20.glGetUniformLocation(program, "uConfidence")
        val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); buffer = ids[0]
    }

    /** Rectangles (view-normalized) the line must stay behind; null clears them. */
    fun setHidden(detected: DetectedBoxes?, width: Int, height: Int) {
        java.util.Arrays.fill(boxes, 0f)
        detected?.let { System.arraycopy(it.rects, 0, boxes, 0, minOf(32, it.count * 4)) }
        viewW = width.toFloat(); viewH = height.toFloat()
    }

    fun drawRibbon(mvp: FloatArray, mesh: ArMeshData, seconds: Float, roadEnd: Float, confidence: Float) {
        GLES20.glUseProgram(program); GLES20.glUniform1i(uMode, 0); GLES20.glUniform1f(uConfidence, confidence); GLES20.glUniform1f(uTime, seconds); GLES20.glUniform1f(uEnd, max(roadEnd, 10f))
        draw(mvp, mesh)
    }

    fun drawSolid(mvp: FloatArray, mesh: ArMeshData, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(program); GLES20.glUniform1i(uMode, 1); GLES20.glUniform4f(uColor, r, g, b, a)
        draw(mvp, mesh)
    }

    private fun draw(mvp: FloatArray, mesh: ArMeshData) {
        if (mesh.vertexCount == 0) return
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uBox, 8, boxes, 0); GLES20.glUniform2f(uViewport, viewW, viewH)
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
