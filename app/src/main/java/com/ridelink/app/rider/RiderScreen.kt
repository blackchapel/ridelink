package com.ridelink.app.rider

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
fun RiderScreen(onBack: () -> Unit) {
    val viewModel: RiderViewModel = viewModel()
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val lastRouteOpenedAt by viewModel.lastRouteOpenedAt.collectAsState()

    // Cosmetic only -- the notification (and the automatic Maps launch) both
    // keep working even if this is denied, the user just won't see the
    // ongoing "listening" notification.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(Unit) {
        viewModel.start()
        // Deliberately a no-op: the whole point of this screen is that
        // RideLink keeps listening -- and can keep launching Google Maps --
        // after you leave it. Ending is an explicit tap on the button below,
        // never a side effect of backgrounding the app.
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
        Text(text = "Rider", style = MaterialTheme.typography.headlineMedium)

        Text(
            text = connectionStatusText(connectionState),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )

        Text(
            text = if (lastRouteOpenedAt != null) {
                "Opened your last route in Google Maps automatically. Ride on -- " +
                    "RideLink keeps listening in the background and will do the " +
                    "same for the next one."
            } else {
                "You can lock your phone and start riding -- RideLink keeps " +
                    "listening in the background and opens Google Maps the " +
                    "moment a route arrives."
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
                VoiceChatControls(voiceChat = voiceChat, peerLabel = "your pillion")
            }
        }
    }
}

private fun connectionStatusText(state: NearbyState): String = when (state) {
    is NearbyState.Idle -> "Starting..."
    is NearbyState.Searching -> "Looking for your pillion's phone..."
    is NearbyState.Connected -> "Connected to ${formatPeerName(state.endpointName)}"
    is NearbyState.Disconnected -> "Disconnected -- reopen this screen to try again"
}
