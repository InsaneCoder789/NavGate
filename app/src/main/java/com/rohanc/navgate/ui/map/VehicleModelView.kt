package com.rohanc.navgate.ui.map

import android.content.Context
import android.app.ActivityManager
import android.graphics.SurfaceTexture
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

internal enum class VehicleRenderStatus { Loading, Ready, Fallback }

/** GLB coordinates are metres, +Y up, and -Z forward; the origin is the ground footprint centre. */
internal data class VehicleViewFrame(
    val yaw: Float,
    val tilt: Float,
    val pixelsPerMetre: Float,
    val centreY: Float,
    val paint: Color,
)

/** Off-centre orthographic bounds keep the ground pivot exactly on the map's projected position. */
internal fun vehicleProjectionBounds(width: Int, height: Int, frame: VehicleViewFrame): DoubleArray {
    val ppm = frame.pixelsPerMetre.toDouble().coerceAtLeast(1e-3)
    val cy = height * frame.centreY.coerceIn(0f, 1f)
    return doubleArrayOf(-width / (2 * ppm), width / (2 * ppm), -(height - cy) / ppm, cy / ppm)
}

/** Clockwise map bearing around glTF's +Y axis, written without Android APIs for deterministic geometry checks. */
internal fun vehicleTransform(yawDegrees: Float, output: FloatArray) {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    val c = cos(yaw).toFloat(); val s = sin(yaw).toFloat()
    output.fill(0f)
    output[0] = c; output[2] = s; output[5] = 1f
    output[8] = -s; output[10] = c; output[15] = 1f
}

/** Six bounded immutable buffers are shared by the map marker and the picker; disk reads stay off the UI thread. */
private object VehicleModelBytes {
    private val cached = ConcurrentHashMap<VehicleModel, ByteArray>()
    fun read(context: Context, model: VehicleModel): ByteArray = cached.getOrPut(model) {
        context.assets.open("vehicles/${model.key}.glb").use { it.readBytes() }
    }
}

/**
 * A transparent native 3D view. Depth testing, normals and glTF materials are handled by Filament,
 * so glass, wheels and bodywork occlude correctly at every heading. The Canvas is a loading/error
 * fallback; it does not replace the real geometry once the first native frame has been presented.
 */
@Composable
internal fun VehicleModelView(
    model: VehicleModel,
    paint: Color,
    yawDegrees: Float,
    pitchDegrees: Float,
    length: Dp,
    modifier: Modifier = Modifier,
    centreYFraction: Float = 0.5f,
    contactShadow: Boolean = true,
    onStatus: (VehicleRenderStatus) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val latestStatus by rememberUpdatedState(onStatus)
    var status by remember { mutableStateOf(VehicleRenderStatus.Loading) }
    val byteState = remember(model) { mutableStateOf<ByteArray?>(null) }
    var readFailed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        try { byteState.value = withContext(Dispatchers.IO) { VehicleModelBytes.read(context, model) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { readFailed = true; Log.w("NavGateVehicle", "Could not read ${model.key}", error) }
    }
    val target = remember(context, lifecycle) {
        try {
            val capabilities = context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo
            require(capabilities.reqGlEsVersion >= 0x30000) { "OpenGL ES 3 is unavailable" }
            VehicleRenderTarget(context) { status = it; latestStatus(it) }
        } catch (error: Exception) {
            Log.w("NavGateVehicle", "Native vehicle renderer unavailable", error)
            null
        } catch (error: LinkageError) {
            Log.w("NavGateVehicle", "Native vehicle library unavailable", error)
            null
        }
    }
    DisposableEffect(target, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> target?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        target?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); target?.close() }
    }
    LaunchedEffect(target, readFailed) {
        if (target == null || readFailed) { status = VehicleRenderStatus.Fallback; latestStatus(status) }
    }
    val ppm = with(density) { length.toPx() } / model.length
    val frame = VehicleViewFrame(
        yawDegrees.takeIf { it.isFinite() } ?: 0f,
        (pitchDegrees.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 75f),
        ppm.coerceAtLeast(0.01f), centreYFraction, paint,
    )
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2, size.height * centreYFraction)
            if (contactShadow) {
                val l = model.length * ppm
                val squash = cos(Math.toRadians(frame.tilt.toDouble())).toFloat().coerceAtLeast(0.35f)
                drawOval(Color(0x330C1A28), Offset(centre.x - l * .35f, centre.y - l * .15f * squash), Size(l * .70f, l * .36f * squash))
            }
            if (status != VehicleRenderStatus.Ready) {
                with(Vehicle3d) { draw(model, paint, centre, frame.yaw, frame.tilt, ppm) }
            }
        }
        if (target != null) AndroidView(
            factory = { target.texture },
            modifier = Modifier.fillMaxSize(),
            update = { target.update(model, byteState.value, frame, readFailed) },
        )
    }
}

/** All native operations, including disposal, are confined to Android's UI thread. */
private object VehicleEnginePool {
    private var current: VehicleEngine? = null
    fun acquire(): VehicleEngine {
        check(Looper.myLooper() == Looper.getMainLooper())
        return (current ?: VehicleEngine().also { current = it }).also { it.references++ }
    }
    fun release(engine: VehicleEngine) {
        check(Looper.myLooper() == Looper.getMainLooper())
        engine.references--
        if (engine.references == 0) { engine.close(); current = null }
    }
}

/** One engine/material provider is shared across the marker, settings thumbnail and visible picker pages. */
private class VehicleEngine : Choreographer.FrameCallback {
    val engine: Engine
    val provider: UbershaderProvider
    val assets: AssetLoader
    val resources: ResourceLoader
    val indirect: IndirectLight
    val lights: IntArray
    val targets = mutableSetOf<VehicleRenderTarget>()
    var references = 0
    private val choreographer = Choreographer.getInstance()
    private var scheduled = false

    init {
        Gltfio.init()
        engine = Engine.create(Engine.Backend.OPENGL)
        provider = UbershaderProvider(engine)
        assets = AssetLoader(engine, provider, EntityManager.get())
        resources = ResourceLoader(engine)
        indirect = IndirectLight.Builder().irradiance(1, floatArrayOf(.55f, .61f, .70f)).intensity(30_000f).build(engine)
        lights = intArrayOf(EntityManager.get().create(), EntityManager.get().create())
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, .97f, .91f).intensity(90_000f)
            .direction(-.4f, -1f, .55f).castShadows(false).build(engine, lights[0])
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(.72f, .84f, 1f).intensity(22_000f)
            .direction(.7f, -.45f, -.65f).castShadows(false).build(engine, lights[1])
    }
    fun schedule() {
        if (!scheduled && targets.any { it.wantsFrame }) { scheduled = true; choreographer.postFrameCallback(this) }
    }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        targets.toList().forEach { it.render(frameTimeNanos) }
        schedule()
    }
    fun close() {
        choreographer.removeFrameCallback(this)
        assets.destroy()
        resources.destroy()
        provider.destroyMaterials(); provider.destroy()
        engine.destroyIndirectLight(indirect)
        lights.forEach { engine.destroyEntity(it); EntityManager.get().destroy(it) }
        engine.destroy()
    }
}

private class VehicleRenderTarget(context: Context, private var report: ((VehicleRenderStatus) -> Unit)?) : AutoCloseable {
    private val owner = VehicleEnginePool.acquire()
    private val engine = owner.engine
    val texture = TextureView(context).apply { isOpaque = false; isClickable = false; isFocusable = false; alpha = 0f; tag = "navgate.vehicle.renderer" }
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity).apply { setExposure(16f, 1f / 125f, 100f) }
    private val view = engine.createView().apply {
        scene = this@VehicleRenderTarget.scene
        camera = this@VehicleRenderTarget.camera
        blendMode = View.BlendMode.TRANSLUCENT
        // Post-process temporal dithering perturbs transparent pixels; MSAA keeps the silhouette
        // independent of the selected paint and leaves the map underneath fully transparent.
        dithering = View.Dithering.NONE
        antiAliasing = View.AntiAliasing.NONE
        multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
    }
    private val display = DisplayHelper(context)
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var requested: VehicleModel? = null
    private var loaded: VehicleModel? = null
    private var frame: VehicleViewFrame? = null
    private var paint: Color? = null
    private var width = 0
    private var height = 0
    private var active = false
    private var closed = false
    private var remainingFrames = 0
    private var ready = false
    private var submitted = false
    private val transform = FloatArray(16)
    val wantsFrame get() = !closed && active && asset != null && ui.isReadyToRender && remainingFrames > 0

    init {
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = floatArrayOf(0f, 0f, 0f, 0f) }
        scene.indirectLight = owner.indirect
        scene.addEntities(owner.lights)
        owner.targets += this
        ui.isOpaque = false
        ui.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, ui.swapChainFlags)
                texture.display?.let { display.attach(renderer, it) }
                invalidate()
            }
            override fun onDetachedFromSurface() {
                display.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
                ready = false; submitted = false
                texture.alpha = 0f
                if (!closed) report?.invoke(VehicleRenderStatus.Loading)
            }
            override fun onResized(w: Int, h: Int) {
                if (closed || w <= 0 || h <= 0) return
                width = w; height = h
                view.viewport = Viewport(0, 0, w, h)
                applyFrame()
                // Resizes must finish pending commands before Android reuses the TextureView surface.
                engine.flushAndWait()
                invalidate()
            }
        }
        ui.attachTo(texture)
        val surfaceEvents = requireNotNull(texture.surfaceTextureListener)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener by surfaceEvents {
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                surfaceEvents.onSurfaceTextureUpdated(surface)
                // endFrame only queues GPU work. Wait until TextureView consumes its buffer before
                // dismissing the fallback, otherwise a freshly selected model can expose the old frame.
                if (!closed && submitted && !ready && asset != null) {
                    ready = true
                    report?.invoke(VehicleRenderStatus.Ready)
                }
            }
        }
    }

    fun setActive(value: Boolean) { if (!closed) { active = value; if (value) invalidate() } }
    private fun invalidate() { remainingFrames = 3; owner.schedule() }

    fun update(model: VehicleModel, bytes: ByteArray?, next: VehicleViewFrame, readFailed: Boolean) {
        if (closed) return
        if (requested != model) {
            requested = model; loaded = null; ready = false; submitted = false; texture.alpha = 0f
            destroyAsset(); report?.invoke(VehicleRenderStatus.Loading)
        }
        if (readFailed) { report?.invoke(VehicleRenderStatus.Fallback); return }
        if (loaded != model && bytes != null) {
            try {
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
                val parsed = requireNotNull(owner.assets.createAsset(buffer)) { "GLB asset could not be parsed" }
                asset = parsed
                owner.resources.loadResources(parsed)
                scene.addEntities(parsed.renderableEntities)
                parsed.releaseSourceData()
                loaded = model; paint = null
            } catch (error: Exception) {
                destroyAsset(); loaded = model
                Log.w("NavGateVehicle", "Could not load ${model.key}", error)
                report?.invoke(VehicleRenderStatus.Fallback)
            }
        }
        if (frame != next || !ready) {
            frame = next
            applyFrame()
            if (paint != next.paint) applyPaint(next.paint)
            invalidate()
        }
    }

    private fun applyPaint(color: Color) {
        asset?.instance?.materialInstances?.forEach { material ->
            val shade = when (material.name) { "BodyPaint" -> 1f; "BodyPaintShade" -> .72f; "ArrowSide" -> .48f; else -> null }
            if (shade != null) material.setParameter("baseColorFactor", Colors.RgbaType.SRGB, color.red * shade, color.green * shade, color.blue * shade, 1f)
        }
        paint = color
    }

    private fun applyFrame() {
        val f = frame ?: return
        if (width <= 0 || height <= 0) return
        val bounds = vehicleProjectionBounds(width, height, f)
        camera.setProjection(Camera.Projection.ORTHO, bounds[0], bounds[1], bounds[2], bounds[3], .05, 100.0)
        val tilt = Math.toRadians(f.tilt.toDouble())
        camera.lookAt(0.0, 20 * cos(tilt), 20 * sin(tilt), 0.0, 0.0, 0.0, 0.0, sin(tilt), -cos(tilt))
        asset?.let {
            // Clockwise navigation bearings become a negative rotation around glTF's +Y axis.
            vehicleTransform(f.yaw, transform)
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(it.root), transform)
        }
    }

    fun render(time: Long) {
        if (!wantsFrame) return
        val swap = swapChain ?: return
        if (renderer.beginFrame(swap, time)) {
            renderer.render(view); renderer.endFrame()
            remainingFrames--
            submitted = true
            texture.alpha = 1f
        }
    }

    private fun destroyAsset() {
        asset?.let { scene.removeEntities(it.renderableEntities); owner.assets.destroyAsset(it) }
        asset = null
    }

    override fun close() {
        if (closed) return
        closed = true; active = false; report = null
        ui.detach()
        destroyAsset()
        owner.targets -= this
        engine.destroyView(view)
        engine.destroyRenderer(renderer)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        VehicleEnginePool.release(owner)
    }
}
