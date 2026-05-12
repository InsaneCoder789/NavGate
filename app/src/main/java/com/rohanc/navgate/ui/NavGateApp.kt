package com.rohanc.navgate.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.ar.ArCoreSupport
import com.rohanc.navgate.ui.theme.NavGateTheme

@Composable
fun NavGateApp() {
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val arCoreSupport = remember { ArCoreSupport() }

    var arState by remember { mutableStateOf<ArAvailabilityState>(ArAvailabilityState.Checking) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(activity) {
        if (activity != null) {
            arCoreSupport.checkAvailability(activity) { arState = it }
        } else {
            arState = ArAvailabilityState.Error("NavGate requires a ComponentActivity host.")
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose { }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        NavGateHome(
            arState = arState,
            hasCameraPermission = hasCameraPermission,
            onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
        )
    }
}

@Composable
private fun NavGateHome(
    arState: ArAvailabilityState,
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF08111E), Color(0xFF123356), Color(0xFF0D9276)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "NavGate",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Native Android foundation for indoor AR navigation.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFD8EEF8),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusRow(label = "ARCore", value = arLabel(arState))
                    StatusRow(
                        label = "Camera",
                        value = if (hasCameraPermission) "Granted" else "Needs permission",
                    )
                    StatusRow(label = "Runtime", value = "Kotlin + Compose + ARCore")

                    HorizontalDivider()

                    when (arState) {
                        ArAvailabilityState.Checking -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                                Text("Checking ARCore availability on this device...")
                            }
                        }

                        ArAvailabilityState.Supported -> {
                            Text(
                                text = if (hasCameraPermission) {
                                    "Foundation is ready. Next we can add the live AR camera session, anchors, and indoor routing pipeline."
                                } else {
                                    "ARCore is available. Grant camera permission to continue with the live view flow."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        ArAvailabilityState.NotInstalled -> {
                            Text(
                                text = "ARCore support is present but Google Play Services for AR still needs installation on the device.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        ArAvailabilityState.ApkTooOld -> {
                            Text(
                                text = "Google Play Services for AR is installed but outdated. Update it before enabling indoor AR navigation.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        ArAvailabilityState.Unsupported -> {
                            Text(
                                text = "This device does not report ARCore support yet. NavGate will need an AR-capable Android device for full indoor navigation.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        is ArAvailabilityState.Error -> {
                            Text(
                                text = arState.message,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    if (!hasCameraPermission) {
                        Button(onClick = onRequestCamera) {
                            Text("Grant camera access")
                        }
                    } else {
                        OutlinedButton(onClick = {}) {
                            Text("AR session scaffold ready")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Suggested next build steps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("1. Add AR session rendering with anchors and hit testing.")
                    Text("2. Add indoor building/floor graph models.")
                    Text("3. Add route-to-anchor conversion for camera guidance.")
                    Text("4. Add Go backend for buildings, POIs, and routing.")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun arLabel(state: ArAvailabilityState): String = when (state) {
    ArAvailabilityState.Checking -> "Checking"
    ArAvailabilityState.Supported -> "Supported"
    ArAvailabilityState.Unsupported -> "Unsupported"
    ArAvailabilityState.ApkTooOld -> "Update required"
    ArAvailabilityState.NotInstalled -> "Install required"
    is ArAvailabilityState.Error -> "Error"
}

@Preview(showBackground = true)
@Composable
private fun NavGatePreview() {
    NavGateTheme {
        NavGateHome(
            arState = ArAvailabilityState.Supported,
            hasCameraPermission = true,
            onRequestCamera = {},
        )
    }
}
