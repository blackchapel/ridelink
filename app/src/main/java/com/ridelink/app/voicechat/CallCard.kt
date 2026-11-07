package com.ridelink.app.voicechat

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridelink.app.R
import com.ridelink.app.ui.theme.RideLinkTheme
import java.util.Locale

/**
 * Everything [CallCard] needs to draw one moment of a voice chat. Plain data,
 * so every state can be previewed without a real call.
 */
data class CallCardModel(
    val state: VoiceChatState,
    /** "Pillion" or "Rider": who is on the other end. */
    val peerRole: String,
    /** The other phone's name, if known. */
    val peerDevice: String? = null,
    /** Call time as text, only used while the call is active. */
    val elapsedText: String = "00:00",
    val isMuted: Boolean = false,
    val output: AudioOutputOption = AudioOutputOption.EARPIECE,
)

/**
 * The in-app voice chat card: avatar, who it is, and the buttons that make
 * sense for the current state --
 *
 * - idle:     green Call
 * - calling:  rings, red Cancel
 * - incoming: rings, red Decline + green Accept
 * - active:   running time, then Mute/Unmute, audio output, red End
 *
 * Stateless: [VoiceChatControls] feeds it from the call session.
 */
@Composable
fun CallCard(
    model: CallCardModel,
    onCall: () -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleOutput: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    animateRings: Boolean = true,
) {
    val colors = callColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
        ) {
            val ringing = model.state == VoiceChatState.OFFERING || model.state == VoiceChatState.INCOMING_OFFER

            Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                if (ringing) {
                    CallRings(color = colors.ring, animate = animateRings, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(colors.avatarBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_person),
                        contentDescription = null,
                        tint = colors.avatarContent,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = model.peerRole,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!model.peerDevice.isNullOrBlank()) {
                Text(
                    text = model.peerDevice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            when (model.state) {
                VoiceChatState.IDLE -> StatusText("Ready to talk")
                VoiceChatState.OFFERING -> StatusText("Calling…")
                VoiceChatState.INCOMING_OFFER -> StatusText("Incoming voice chat")
                VoiceChatState.ACTIVE -> {
                    Text(
                        text = model.elapsedText,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 34.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (model.isMuted) {
                        Spacer(Modifier.height(8.dp))
                        MutedTag(colors)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            when (model.state) {
                VoiceChatState.IDLE -> CallButton(
                    icon = R.drawable.ic_call,
                    label = "Call",
                    description = "Start voice chat",
                    container = colors.green,
                    content = Color.White,
                    size = 72.dp,
                    onClick = onCall,
                    modifier = Modifier.width(96.dp),
                )

                VoiceChatState.OFFERING -> CallButton(
                    icon = R.drawable.ic_call_end,
                    label = "Cancel",
                    description = "Cancel call",
                    container = colors.red,
                    content = Color.White,
                    size = 72.dp,
                    onClick = onCancel,
                    modifier = Modifier.width(96.dp),
                )

                VoiceChatState.INCOMING_OFFER -> Row(modifier = Modifier.fillMaxWidth()) {
                    CallButton(
                        icon = R.drawable.ic_call_end,
                        label = "Decline",
                        description = "Decline voice chat",
                        container = colors.red,
                        content = Color.White,
                        size = 72.dp,
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                    )
                    CallButton(
                        icon = R.drawable.ic_call,
                        label = "Accept",
                        description = "Accept voice chat",
                        container = colors.green,
                        content = Color.White,
                        size = 72.dp,
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                    )
                }

                VoiceChatState.ACTIVE -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (model.isMuted) {
                        CallButton(
                            icon = R.drawable.ic_mic_off,
                            label = "Unmute",
                            description = "Unmute microphone",
                            container = colors.solid,
                            content = colors.solidContent,
                            size = 64.dp,
                            onClick = onToggleMute,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CallButton(
                            icon = R.drawable.ic_mic,
                            label = "Mute",
                            description = "Mute microphone",
                            container = colors.tonal,
                            content = colors.tonalContent,
                            size = 64.dp,
                            onClick = onToggleMute,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CallButton(
                        icon = model.output.icon(),
                        label = model.output.label(),
                        description = "Change audio output, now ${model.output.label()}",
                        container = colors.tonal,
                        content = colors.tonalContent,
                        size = 64.dp,
                        onClick = onCycleOutput,
                        modifier = Modifier.weight(1f),
                    )
                    CallButton(
                        icon = R.drawable.ic_call_end,
                        label = "End",
                        description = "End voice chat",
                        container = colors.red,
                        content = Color.White,
                        size = 64.dp,
                        onClick = onEnd,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Round icon button with its label underneath, like a phone's call screen. */
@Composable
private fun CallButton(
    @DrawableRes icon: Int,
    label: String,
    description: String,
    container: Color,
    content: Color,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = content,
                modifier = Modifier.size(size * 0.46f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** Two expanding, fading rings behind the avatar while a call is ringing. */
@Composable
private fun CallRings(color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "call rings")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing), RepeatMode.Restart),
        label = "call ring phase",
    )
    Canvas(modifier = modifier) {
        val outer = size.minDimension / 2f
        val inner = outer * 0.62f
        for (offset in listOf(0f, 0.5f)) {
            // Reduce motion: draw the rings once, frozen, instead of animating them.
            val progress = if (animate) (phase + offset) % 1f else offset + 0.25f
            drawCircle(
                color = color.copy(alpha = (1f - progress) * 0.5f),
                radius = inner + (outer - inner) * progress,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MutedTag(colors: CallColors) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.tagBackground,
        border = BorderStroke(1.dp, colors.tagBorder),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(28.dp).padding(start = 8.dp, end = 12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic_off),
                contentDescription = null,
                tint = colors.tagContent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "You are muted",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = colors.tagContent,
            )
        }
    }
}

private class CallColors(
    val green: Color,
    val red: Color,
    val tonal: Color,
    val tonalContent: Color,
    val solid: Color,
    val solidContent: Color,
    val tagBackground: Color,
    val tagBorder: Color,
    val tagContent: Color,
    val ring: Color,
    val avatarBackground: Color,
    val avatarContent: Color,
)

private val LightCallColors = CallColors(
    green = Color(0xFF1E8E3E),
    red = Color(0xFFD93025),
    tonal = Color(0xFFE4EBF7),
    tonalContent = Color(0xFF1555A8),
    solid = Color(0xFF1B2B3F),
    solidContent = Color.White,
    tagBackground = Color(0xFFFFF4E0),
    tagBorder = Color(0xFFF0C987),
    tagContent = Color(0xFF7A4300),
    ring = Color(0xFF1565C0),
    avatarBackground = Color(0xFFE3ECFA),
    avatarContent = Color(0xFF1565C0),
)

private val DarkCallColors = CallColors(
    green = Color(0xFF2E9D50),
    red = Color(0xFFE5483B),
    tonal = Color(0xFF2A3546),
    tonalContent = Color(0xFF90CAF9),
    solid = Color(0xFFE8ECF2),
    solidContent = Color(0xFF101820),
    tagBackground = Color(0xFF3A2D14),
    tagBorder = Color(0xFF6B531F),
    tagContent = Color(0xFFF6D28A),
    ring = Color(0xFF90CAF9),
    avatarBackground = Color(0xFF22344F),
    avatarContent = Color(0xFF90CAF9),
)

/** The app has its own dark toggle, so ask the theme, not the system. */
@Composable
private fun callColors(): CallColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkCallColors else LightCallColors

@DrawableRes
private fun AudioOutputOption.icon(): Int = when (this) {
    AudioOutputOption.SPEAKER -> R.drawable.ic_volume_up
    AudioOutputOption.EARPIECE -> R.drawable.ic_phone_earpiece
    AudioOutputOption.BLUETOOTH -> R.drawable.ic_bluetooth_audio
}

private fun AudioOutputOption.label(): String = when (this) {
    AudioOutputOption.SPEAKER -> "Speaker"
    AudioOutputOption.EARPIECE -> "Call speaker"
    AudioOutputOption.BLUETOOTH -> "Bluetooth"
}

/** "04:12", or "1:04:12" once a call passes an hour. */
internal fun formatCallDuration(elapsedMillis: Long): String {
    val total = (elapsedMillis / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

// ---- Previews: every state, caller and receiver, light and dark -------------------

@Composable
private fun PreviewCard(model: CallCardModel, dark: Boolean = false) {
    RideLinkTheme(isDarkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CallCard(
                model = model,
                onCall = {},
                onCancel = {},
                onAccept = {},
                onDecline = {},
                onToggleMute = {},
                onCycleOutput = {},
                onEnd = {},
                modifier = Modifier.padding(16.dp),
                animateRings = false,
            )
        }
    }
}

private val CallerIdle = CallCardModel(VoiceChatState.IDLE, "Pillion", "Pixel 8")
private val CallerCalling = CallCardModel(VoiceChatState.OFFERING, "Pillion", "Pixel 8")
private val CallerActive = CallCardModel(
    VoiceChatState.ACTIVE, "Pillion", "Pixel 8", elapsedText = "04:12", output = AudioOutputOption.SPEAKER,
)
private val ReceiverIdle = CallCardModel(VoiceChatState.IDLE, "Rider", "Galaxy S25")
private val ReceiverIncoming = CallCardModel(VoiceChatState.INCOMING_OFFER, "Rider", "Galaxy S25")
private val ReceiverActive = CallCardModel(
    VoiceChatState.ACTIVE, "Rider", "Galaxy S25", elapsedText = "04:12", output = AudioOutputOption.BLUETOOTH,
)

@Preview(name = "Caller: idle", showBackground = true, widthDp = 360)
@Composable
private fun PreviewCallerIdle() = PreviewCard(CallerIdle)

@Preview(name = "Caller: calling", showBackground = true, widthDp = 360)
@Composable
private fun PreviewCallerCalling() = PreviewCard(CallerCalling)

@Preview(name = "Caller: connected", showBackground = true, widthDp = 360)
@Composable
private fun PreviewCallerActive() = PreviewCard(CallerActive)

@Preview(name = "Caller: connected, muted", showBackground = true, widthDp = 360)
@Composable
private fun PreviewCallerMuted() = PreviewCard(CallerActive.copy(isMuted = true, elapsedText = "04:31"))

@Preview(name = "Receiver: idle", showBackground = true, widthDp = 360)
@Composable
private fun PreviewReceiverIdle() = PreviewCard(ReceiverIdle)

@Preview(name = "Receiver: incoming", showBackground = true, widthDp = 360)
@Composable
private fun PreviewReceiverIncoming() = PreviewCard(ReceiverIncoming)

@Preview(name = "Receiver: connected", showBackground = true, widthDp = 360)
@Composable
private fun PreviewReceiverActive() = PreviewCard(ReceiverActive)

@Preview(name = "Receiver: connected, muted", showBackground = true, widthDp = 360)
@Composable
private fun PreviewReceiverMuted() = PreviewCard(ReceiverActive.copy(isMuted = true, elapsedText = "04:31"))

@Preview(name = "Dark: incoming", showBackground = true, widthDp = 360)
@Composable
private fun PreviewDarkIncoming() = PreviewCard(ReceiverIncoming, dark = true)

@Preview(name = "Dark: connected, muted", showBackground = true, widthDp = 360)
@Composable
private fun PreviewDarkMuted() = PreviewCard(CallerActive.copy(isMuted = true), dark = true)
