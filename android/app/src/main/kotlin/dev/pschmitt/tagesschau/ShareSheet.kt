@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.time.format.DateTimeFormatter

/**
 * The web player's "Diese Stelle teilen": a preview, a slider in broadcast time with the exact
 * second, nudges of 1 and 5 s, "start here" and the ?at= link to copy or share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(activity: MainActivity, st: PlayState, streamUrl: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val start = st.start ?: return onDismiss()
    val span = st.span.takeIf { it > 0 } ?: st.position.coerceAtLeast(1)
    var ms by remember { mutableLongStateOf(st.position.coerceIn(0, span)) }
    var fromHere by remember { mutableStateOf(true) }
    val copiedText = stringResource(R.string.copied)
    val shareTitle = stringResource(R.string.share)

    // The preview plays the 540p file (or the live stream) muted, seeking to the nearest key frame.
    val preview = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            val url = streamUrl?.replace(Regex("""\.web[a-z]+\.h264\.mp4$"""), ".webm.h264.mp4")
            if (url != null) {
                setMediaItem(MediaItem.fromUri(url), ms)
                prepare()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { preview.release() } }

    val air = start.plusNanos(ms * 1_000_000).atZoneSameInstant(Edition.BERLIN)
    val linkSeconds = if (fromHere) air.toEpochSecond() else start.toEpochSecond()
    val url = "${Settings(context).serverUrl}/?at=$linkSeconds"
    val title =
        "tagesschau ${start.atZoneSameInstant(Edition.BERLIN).format(HM)} Uhr, ${
        start.atZoneSameInstant(Edition.BERLIN).format(DAY)}"

    fun seek(to: Long) {
        ms = to.coerceIn(0, span)
        preview.seekTo(ms)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Deep,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.share), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Palette.Mist)
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        player = preview
                    }
                },
                modifier =
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
            )
            Text(air.format(HMS), fontSize = 40.sp, fontWeight = FontWeight.Black)
            Slider(
                value = ms.toFloat(),
                onValueChange = { seek(it.toLong()) },
                valueRange = 0f..span.toFloat(),
                colors =
                    SliderDefaults.colors(
                        thumbColor = Palette.Signal,
                        activeTrackColor = Palette.Signal,
                        inactiveTrackColor = Palette.Mist.copy(alpha = 0.3f),
                    ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5, -1, 1, 5).forEach { n ->
                    FilledTonalButton(onClick = { seek(ms + n * 1000L) }) {
                        Text((if (n < 0) "−" else "+") + "${kotlin.math.abs(n)} s")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.share_from_here), Modifier.weight(1f))
                Switch(fromHere, { fromHere = it })
            }
            OutlinedTextField(
                url,
                {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(title, url))
                        activity.toast(copiedText)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Palette.Signal,
                            contentColor = Palette.Deep,
                        ),
                ) {
                    Text(stringResource(R.string.copy))
                }
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, title)
                                    .putExtra(Intent.EXTRA_TEXT, "$title: $url"),
                                shareTitle,
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.share_send))
                }
            }
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", java.util.Locale.GERMANY)
