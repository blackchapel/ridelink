package com.ridelink.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ridelink.app.notifications.ConnectionRole
import com.ridelink.app.notifications.NotificationActionReceiver
import com.ridelink.app.permissions.PermissionsAndRadioGate
import com.ridelink.app.pillion.PillionScreen
import com.ridelink.app.pillion.PillionSession
import com.ridelink.app.rider.RiderScreen
import com.ridelink.app.rider.RiderSession
import com.ridelink.app.ui.theme.RideLinkTheme
import com.ridelink.app.ui.theme.ThemePreferences

private enum class Role { RIDER, PILLION }

/** Carries a route link from RiderForegroundService's route notification. */
const val EXTRA_ROUTE_LINK = "com.ridelink.app.EXTRA_ROUTE_LINK"

/**
 * Carried by the notification's Call / Accept buttons while the microphone
 * permission is missing: which action to run once the user has granted it,
 * and for which role's session (see ConnectionNotificationBuilder.micAction).
 */
const val EXTRA_VOICE_ACTION = "com.ridelink.app.EXTRA_VOICE_ACTION"
const val EXTRA_VOICE_ROLE = "com.ridelink.app.EXTRA_VOICE_ROLE"

class MainActivity : ComponentActivity() {

    // A Call / Accept tapped in the notification before the microphone was
    // allowed: held here while the permission prompt is up.
    private var pendingVoiceAction: Pair<ConnectionRole, String>? = null

    // Lint's Fragment-version check is about FragmentActivity; this is a plain
    // ComponentActivity, so it does not apply.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingVoiceAction
        pendingVoiceAction = null
        if (granted && pending != null) runVoiceAction(pending.first, pending.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RideLinkApp()
        }
        handleRouteIntent(intent)
        handleVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRouteIntent(intent)
        handleVoiceIntent(intent)
    }

    private fun handleVoiceIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_VOICE_ACTION) ?: return
        val role = intent.getStringExtra(EXTRA_VOICE_ROLE)
            ?.let { runCatching { ConnectionRole.valueOf(it) }.getOrNull() } ?: return
        // Clear so a later recreation (e.g. rotation) doesn't fire it again.
        intent.removeExtra(EXTRA_VOICE_ACTION)
        intent.removeExtra(EXTRA_VOICE_ROLE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runVoiceAction(role, action)
        } else {
            pendingVoiceAction = role to action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Only acts if the call is still in the state the button was for.
    private fun runVoiceAction(role: ConnectionRole, action: String) {
        val voiceChat = when (role) {
            ConnectionRole.RIDER -> RiderSession.voiceChat
            ConnectionRole.PILLION -> PillionSession.voiceChat
        }
        when (action) {
            NotificationActionReceiver.ACTION_OFFER -> voiceChat?.offer()
            NotificationActionReceiver.ACTION_ACCEPT -> voiceChat?.accept()
        }
    }

    // Tapping the "new route" notification launches this Activity (a
    // notification tap has no background-activity-start restriction, unlike
    // launching Google Maps directly from a background service) carrying the
    // route link; once we're genuinely in the foreground, relaunching Maps
    // from here has no such restriction either.
    private fun handleRouteIntent(intent: Intent?) {
        val link = intent?.getStringExtra(EXTRA_ROUTE_LINK) ?: return
        if (!RiderSession.openInGoogleMaps(this, link)) {
            Toast.makeText(this, "Couldn't open the route. Is Google Maps installed?", Toast.LENGTH_LONG).show()
        }
        // Clear it so a later recreation (e.g. rotation) doesn't refire this.
        intent.removeExtra(EXTRA_ROUTE_LINK)
    }
}

@Composable
private fun RideLinkApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val activityWindow = (context as? ComponentActivity)?.window
    val themePreferences = remember { ThemePreferences(context) }
    val systemDarkTheme = isSystemInDarkTheme()
    var isDarkTheme by remember {
        mutableStateOf(themePreferences.isDarkTheme(systemDefault = systemDarkTheme))
    }

    // Android only shows readable status/nav bar icons if the app tells it
    // which style matches the app's own current background -- this has to
    // re-run every time the theme toggle changes, not just once at launch.
    LaunchedEffect(isDarkTheme, activityWindow) {
        val window = activityWindow ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme
        insetsController.isAppearanceLightNavigationBars = !isDarkTheme
    }

    RideLinkTheme(isDarkTheme = isDarkTheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Survives rotation / activity recreation, and starts on the role
            // whose session is already running (e.g. opened from the notification).
            var role by rememberSaveable {
                mutableStateOf(
                    when {
                        RiderSession.isActive.value -> Role.RIDER
                        PillionSession.isActive.value -> Role.PILLION
                        else -> null
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                AppHeader(
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        themePreferences.setDarkTheme(isDarkTheme)
                    },
                )
                PermissionsAndRadioGate {
                    when (role) {
                        null -> RolePickerScreen(onRoleSelected = { role = it })
                        Role.PILLION -> PillionScreen(onBack = { role = null })
                        Role.RIDER -> RiderScreen(onBack = { role = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(isDarkTheme: Boolean, onToggleTheme: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "RideLink", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isDarkTheme) "Dark" else "Light",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(checked = isDarkTheme, onCheckedChange = { onToggleTheme() })
        }
    }
}

@Composable
private fun RolePickerScreen(onRoleSelected: (Role) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Who's on this phone for this ride?", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Pick a role below -- the other phone should pick the other one.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        Button(
            onClick = { onRoleSelected(Role.RIDER) },
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Text("I'm the Rider")
        }

        Button(
            onClick = { onRoleSelected(Role.PILLION) },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(top = 16.dp),
        ) {
            Text("I'm the Pillion")
        }

        LastCrashNotice()
    }
}

/**
 * Shown on the role picker only if the app crashed (or swallowed a
 * background error) last time, so the report can be sent without adb.
 */
@Composable
private fun LastCrashNotice() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return

    Column(
        modifier = Modifier.padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "RideLink hit an error last time. Sharing the report helps fix it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "RideLink crash report")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    // Keep RideLink's own share target (route capture) out of the list.
                    val chooser = Intent.createChooser(send, "Share crash report").apply {
                        putExtra(
                            Intent.EXTRA_EXCLUDE_COMPONENTS,
                            arrayOf(ComponentName(context, ShareReceiverActivity::class.java)),
                        )
                    }
                    runCatching { context.startActivity(chooser) }
                },
            ) { Text("Share report") }
            TextButton(
                onClick = {
                    CrashLog.clear(context)
                    report = null
                },
            ) { Text("Dismiss") }
        }
    }
}
