package dev.pschmitt.tagesschau

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tasker (Locale API) action "play the latest tagesschau". Tasker opens [TaskerEditActivity] to
 * configure it and broadcasts FIRE_SETTING to [TaskerFireReceiver] when the action runs.
 */
object TaskerPlugin {
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val KEY_PREFER_LIVE = "dev.pschmitt.tagesschau.PREFER_LIVE"
    const val KEY_AUDIO_ONLY = "dev.pschmitt.tagesschau.AUDIO_ONLY"
}

class TaskerEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE)
        setContent {
            TagesschauTheme {
                var preferLive by remember {
                    mutableStateOf(old?.getBoolean(TaskerPlugin.KEY_PREFER_LIVE, true) ?: true)
                }
                var audioOnly by remember {
                    mutableStateOf(old?.getBoolean(TaskerPlugin.KEY_AUDIO_ONLY, true) ?: true)
                }
                Surface(Modifier.fillMaxSize(), color = Palette.Studio) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.tasker_action),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.tasker_action_help), color = Palette.Mist)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.prefer_live), Modifier.weight(1f))
                            Switch(preferLive, { preferLive = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tasker_audio_only), Modifier.weight(1f))
                            Switch(audioOnly, { audioOnly = it })
                        }
                        Button(onClick = { save(preferLive, audioOnly) }) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    private fun save(preferLive: Boolean, audioOnly: Boolean) {
        val bundle =
            Bundle().apply {
                putBoolean(TaskerPlugin.KEY_PREFER_LIVE, preferLive)
                putBoolean(TaskerPlugin.KEY_AUDIO_ONLY, audioOnly)
            }
        val blurb =
            buildList {
                    add(getString(R.string.tasker_action))
                    if (preferLive) add(getString(R.string.prefer_live))
                    if (audioOnly) add(getString(R.string.tasker_audio_only))
                }
                .joinToString("\n")
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(TaskerPlugin.EXTRA_BUNDLE, bundle)
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
        val bundle = intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE)
        val start =
            Intent(context, PlayLatestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(
                    PlaybackService.EXTRA_PREFER_LIVE,
                    bundle?.getBoolean(TaskerPlugin.KEY_PREFER_LIVE, true) ?: true,
                )
                .putExtra(
                    PlaybackService.EXTRA_AUDIO_ONLY,
                    bundle?.getBoolean(TaskerPlugin.KEY_AUDIO_ONLY, true) ?: true,
                )
        runCatching { context.startActivity(start) }
            .onFailure { Log.w("TaskerFire", "could not start playback", it) }
    }
}
