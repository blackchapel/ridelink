package com.ridelink.app.notifications

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Whether Android currently lets RideLink post "Live Update" (promoted)
 * notifications, which is what the status-bar pill / Samsung Now Bar need.
 * Shown on the role screens so a test build tells us what the phone does,
 * with a shortcut to the system switch. Hidden on Android below 16.
 */
@Composable
fun NowBarStatusLine() {
    if (Build.VERSION.SDK_INT < 36) return

    val context = LocalContext.current
    // Re-check every time the user comes back (e.g. from the system switch).
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // Read on every recomposition that follows a refresh. runCatching: the
    // call is brand new API, and an Error (not just an Exception) on an
    // unexpected platform build must not crash the screen.
    val allowed = remember(refresh) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        }.getOrNull()
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = when (allowed) {
                true -> "Live Updates (Now Bar / status pill): allowed"
                false -> "Live Updates (Now Bar / status pill): not allowed for RideLink"
                null -> "Live Updates (Now Bar / status pill): status unavailable"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (allowed != true) {
            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: ActivityNotFoundException) {
                        // No such settings screen on this phone -- nothing to open.
                    }
                },
            ) {
                Text("Open Live Update settings")
            }
        }
    }
}
