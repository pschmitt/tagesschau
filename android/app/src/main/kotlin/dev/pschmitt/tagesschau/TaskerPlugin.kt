package dev.pschmitt.tagesschau

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tasker (Locale API) action "play tagesschau". Tasker opens [TaskerEditActivity] to configure it
 * and broadcasts FIRE_SETTING to [TaskerFireReceiver] when the action runs. Each action keeps its
 * own options, the same as Bluetooth auto-play has (see [AutoStart.Options]).
 */
object TaskerPlugin {
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val KEY_TARGET = "dev.pschmitt.tagesschau.TARGET"
    const val KEY_PREFER_LIVE = "dev.pschmitt.tagesschau.PREFER_LIVE"
    const val KEY_MODE = "dev.pschmitt.tagesschau.MODE"
    const val KEY_RESUME = "dev.pschmitt.tagesschau.RESUME"
    const val KEY_VOLUME = "dev.pschmitt.tagesschau.VOLUME"
    /** Older actions: sound only or not (now [KEY_MODE]). */
    const val KEY_AUDIO_ONLY = "dev.pschmitt.tagesschau.AUDIO_ONLY"

    fun options(b: Bundle?): AutoStart.Options {
        if (b == null) return AutoStart.Options()
        val mode =
            b.getString(KEY_MODE)?.let { runCatching { BluetoothMode.valueOf(it) }.getOrNull() }
                ?: if (b.getBoolean(KEY_AUDIO_ONLY, true)) BluetoothMode.AUDIO
                else BluetoothMode.VIDEO
        return AutoStart.Options(
            target =
                b.getString(KEY_TARGET)?.let { runCatching { PlayTarget.valueOf(it) }.getOrNull() }
                    ?: PlayTarget.TAGESSCHAU,
            preferLive = b.getBoolean(KEY_PREFER_LIVE, true),
            mode = mode,
            resume = b.getBoolean(KEY_RESUME, true),
            volume = b.getInt(KEY_VOLUME, -1),
        )
    }

    fun bundle(o: AutoStart.Options) =
        Bundle().apply {
            putString(KEY_TARGET, o.target.name)
            putBoolean(KEY_PREFER_LIVE, o.preferLive)
            putString(KEY_MODE, o.mode.name)
            putBoolean(KEY_RESUME, o.resume)
            putInt(KEY_VOLUME, o.volume)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
class TaskerEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = TaskerPlugin.options(intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE))
        setContent {
            TagesschauTheme {
                var target by remember { mutableStateOf(old.target) }
                var preferLive by remember { mutableStateOf(old.preferLive) }
                var mode by remember { mutableStateOf(old.mode) }
                var resume by remember { mutableStateOf(old.resume) }
                var volumeOn by remember { mutableStateOf(old.volume >= 0) }
                var volume by remember {
                    mutableIntStateOf(old.volume.takeIf { it >= 0 } ?: 50)
                }
                val colors = ListItemDefaults.colors(containerColor = Palette.Studio)
                Surface(Modifier.fillMaxSize(), color = Palette.Studio) {
                    // Clear of the status and navigation bars; scrolls on small screens.
                    Column(
                        Modifier.safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.tasker_action),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Text(
                            stringResource(R.string.tasker_action_help),
                            color = Palette.Mist,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.bt_target)) },
                            colors = colors,
                        )
                        Segments(
                            listOf(
                                PlayTarget.TAGESSCHAU to R.string.play_target_tagesschau,
                                PlayTarget.CHANNEL to R.string.play_target_channel,
                            ),
                            target,
                        ) {
                            target = it
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.bt_mode)) },
                            colors = colors,
                        )
                        Segments(
                            listOf(
                                BluetoothMode.AUDIO to R.string.bt_mode_audio,
                                BluetoothMode.VIDEO to R.string.bt_mode_video,
                                BluetoothMode.PIP to R.string.bt_mode_pip,
                            ),
                            mode,
                        ) {
                            mode = it
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.prefer_live)) },
                            supportingContent = { Text(stringResource(R.string.prefer_live_help)) },
                            trailingContent = {
                                Switch(
                                    preferLive,
                                    { preferLive = it },
                                    enabled = target == PlayTarget.TAGESSCHAU,
                                )
                            },
                            colors = colors,
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.bt_resume)) },
                            supportingContent = { Text(stringResource(R.string.bt_resume_help)) },
                            trailingContent = {
                                Switch(
                                    resume,
                                    { resume = it },
                                    enabled = target == PlayTarget.TAGESSCHAU,
                                )
                            },
                            colors = colors,
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.bt_volume)) },
                            supportingContent = {
                                Text(
                                    if (volumeOn) stringResource(R.string.bt_volume_value, volume)
                                    else stringResource(R.string.bt_volume_off)
                                )
                            },
                            trailingContent = { Switch(volumeOn, { volumeOn = it }) },
                            colors = colors,
                        )
                        Slider(
                            value = volume.toFloat(),
                            onValueChange = { volume = it.toInt() },
                            valueRange = 0f..100f,
                            enabled = volumeOn,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Button(
                            onClick = {
                                save(
                                    AutoStart.Options(
                                        target = target,
                                        preferLive = preferLive,
                                        mode = mode,
                                        resume = resume,
                                        volume = if (volumeOn) volume else -1,
                                    )
                                )
                            },
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun <T> Segments(items: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            items.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, items.size),
                ) {
                    Text(stringResource(label), fontSize = 13.sp)
                }
            }
        }
    }

    private fun save(o: AutoStart.Options) {
        val blurb =
            buildList {
                    add(
                        getString(
                            if (o.target == PlayTarget.CHANNEL) R.string.play_target_channel
                            else R.string.play_target_tagesschau
                        )
                    )
                    add(
                        getString(
                            when (o.mode) {
                                BluetoothMode.AUDIO -> R.string.bt_mode_audio
                                BluetoothMode.VIDEO -> R.string.bt_mode_video
                                BluetoothMode.PIP -> R.string.bt_mode_pip
                            }
                        )
                    )
                    if (o.target == PlayTarget.TAGESSCHAU && o.preferLive)
                        add(getString(R.string.prefer_live))
                    if (o.volume >= 0) add(getString(R.string.bt_volume_value, o.volume))
                }
                .joinToString("\n")
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(TaskerPlugin.EXTRA_BUNDLE, TaskerPlugin.bundle(o))
                .putExtra(TaskerPlugin.EXTRA_BLURB, blurb),
        )
        finish()
    }
}

/**
 * Runs the action. A broadcast receiver may not start a foreground service on Android 12+, so it
 * starts the invisible [PlayLatestActivity] instead. Starting an activity from the background needs
 * the "display over other apps" permission (see the app's settings).
 */
class TaskerFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val o = TaskerPlugin.options(intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE))
        runCatching { context.startActivity(AutoStart.intent(context, o)) }
            .onFailure { Log.w("TaskerFire", "could not start playback", it) }
    }
}
