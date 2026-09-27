package com.ridelink.app.pillion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.nearby.formatPeerName
import com.ridelink.app.voicechat.VoiceChatControls

@Composable
fun PillionScreen(onBack: () -> Unit) {
    val viewModel: PillionViewModel = viewModel()
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val capturedLink by viewModel.capturedLink.collectAsState()
    val lastSentLink by viewModel.lastSentLink.collectAsState()

    // Cosmetic only -- the notification keeps working even if this is
    // denied, the user just won't see it.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Pillion", style = MaterialTheme.typography.headlineMedium)

        Text(
            text = connectionStatusText(connectionState),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )

        Text(
            text = when {
                capturedLink != null && capturedLink == lastSentLink ->
                    "Route sent to your rider. Share another any time -- it'll go straight through."
                capturedLink != null ->
                    "Route captured -- sending as soon as you're connected..."
                else ->
                    "Open Google Maps, build your route, then tap Share → RideLink."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        TextButton(
            onClick = {
                viewModel.stop()
                onBack()
            },
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text("End")
        }

        if (connectionState is NearbyState.Connected) {
            viewModel.voiceChat?.let { voiceChat ->
                VoiceChatControls(voiceChat = voiceChat, peerLabel = "your rider")
            }
        }
    }
}

private fun connectionStatusText(state: NearbyState): String = when (state) {
    is NearbyState.Idle -> "Starting..."
    is NearbyState.Searching -> "Waiting for your rider to connect..."
    is NearbyState.Connected -> "Connected to ${formatPeerName(state.endpointName)}"
    is NearbyState.Disconnected -> "Disconnected -- reopen this screen to try again"
}
