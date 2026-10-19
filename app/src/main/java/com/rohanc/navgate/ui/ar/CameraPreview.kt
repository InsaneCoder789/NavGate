package com.rohanc.navgate.ui.ar

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var unavailable by remember(previewView, lifecycleOwner) { mutableStateOf<String?>(null) }

    // Binding is owned by this composition and lifecycle, rather than AndroidView.update:
    // heading/location recompositions must not restart the camera or unbind other use cases.
    DisposableEffect(previewView, lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var ownedPreview: Preview? = null
        var cameraStates: androidx.lifecycle.LiveData<CameraState>? = null
        var cameraObserver: Observer<CameraState>? = null
        val future = try { ProcessCameraProvider.getInstance(context.applicationContext) }
        catch (_: Exception) {
            unavailable = "Camera preview could not start. Return to the map and try camera guidance again."
            null
        }
        if (future != null) future.addListener({
            if (!disposed && lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                try {
                    val ready = future.get()
                    provider = ready
                    if (!ready.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        unavailable = "A rear camera is unavailable on this device. Return to the map for guidance."
                    } else {
                        val preview = Preview.Builder().build()
                        ownedPreview = preview
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val camera = ready.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        val observer = Observer<CameraState> { state ->
                            if (!disposed) {
                                unavailable = if (state.error != null) {
                                    "Camera preview is unavailable. Return to the map and try camera guidance again."
                                } else null
                            }
                        }
                        cameraStates = camera.cameraInfo.cameraState
                        cameraObserver = observer
                        camera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                    }
                } catch (_: SecurityException) {
                    unavailable = "Camera access was denied. Allow camera access in Android settings or return to the map."
                } catch (_: Exception) {
                    unavailable = "Camera preview could not start. Return to the map and try camera guidance again."
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            cameraObserver?.let { cameraStates?.removeObserver(it) }
            ownedPreview?.let { preview ->
                preview.setSurfaceProvider(null)
                // Never call unbindAll(): the provider is shared with other camera consumers.
                runCatching { provider?.unbind(preview) }
            }
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
        unavailable?.let { message ->
            Text(message, modifier = Modifier.background(Color.Black.copy(alpha = 0.85f)).padding(24.dp),
                color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
