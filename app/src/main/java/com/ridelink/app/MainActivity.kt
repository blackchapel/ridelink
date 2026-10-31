package com.ridelink.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.view.WindowCompat
import com.ridelink.app.permissions.PermissionsAndRadioGate
import com.ridelink.app.pillion.PillionScreen
import com.ridelink.app.rider.RiderScreen
import com.ridelink.app.rider.RiderSession
import com.ridelink.app.ui.theme.RideLinkTheme
import com.ridelink.app.ui.theme.ThemePreferences

private enum class Role { RIDER, PILLION }

/** Carries a route link from RiderForegroundService's route notification. */
const val EXTRA_ROUTE_LINK = "com.ridelink.app.EXTRA_ROUTE_LINK"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RideLinkApp()
        }
        handleRouteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRouteIntent(intent)
    }

    // Tapping the "new route" notification launches this Activity (a
    // notification tap has no background-activity-start restriction, unlike
    // launching Google Maps directly from a background service) carrying the
    // route link; once we're genuinely in the foreground, relaunching Maps
    // from here has no such restriction either.
    private fun handleRouteIntent(intent: Intent?) {
        val link = intent?.getStringExtra(EXTRA_ROUTE_LINK) ?: return
        RiderSession.openInGoogleMaps(this, link)
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
            var role by remember { mutableStateOf<Role?>(null) }

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
    }
}
