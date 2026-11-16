@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import dev.pschmitt.tagesschau.update.ShizukuInstaller
import dev.pschmitt.tagesschau.update.ShizukuState
import dev.pschmitt.tagesschau.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { TagesschauTheme { EditionsScreen(this) } }
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) installUpdate()
    }

    fun installUpdate() {
        lifecycleScope.launch {
            val update = runCatching { Updater.check() }.getOrNull()
            if (update == null) {
                toast(getString(R.string.update_none))
                return@launch
            }
            toast(getString(R.string.update_installing))
            if (!runCatching { Updater.install(this@MainActivity, update) }.getOrDefault(false)) {
                toast(getString(R.string.update_failed))
            }
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    fun play(editions: List<Edition>, index: Int) {
        startActivity(PlayerActivity.intent(this, editions, index))
    }

    companion object {
        const val EXTRA_INSTALL_UPDATE = "install_update"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditionsScreen(activity: MainActivity) {
    val settings = remember { Settings(activity) }
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Download>() }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { Api(settings.serverUrl).listing() }
                .onSuccess {
                    listing = it
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) {
        val manager = Downloads.manager(activity)
        downloads.putAll(Downloads.all(activity))
        val listener =
            object : DownloadManager.Listener {
                override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) {
                    downloads[d.request.id] = d
                }

                override fun onDownloadRemoved(m: DownloadManager, d: Download) {
                    downloads.remove(d.request.id)
                }
            }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }

    // Online: the server's list. Offline: whatever is downloaded.
    val editions: List<Edition> =
        listing?.all
            ?: downloads.values
                .filter { it.state == Download.STATE_COMPLETED }
                .mapNotNull(Downloads::edition)
                .sortedByDescending { it.date }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("tagesschau", fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.width(8.dp))
                        Text("alle Ausgaben", color = Palette.Mist, fontSize = 16.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            stringResource(R.string.settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Studio),
            )
        },
        containerColor = Palette.Studio,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = ::refresh,
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (error != null) {
                    item {
                        Text(
                            stringResource(R.string.load_failed, error.orEmpty()),
                            color = Palette.Mist,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                var lastDay: String? = null
                editions.forEachIndexed { index, e ->
                    if (e.day != lastDay) {
                        lastDay = e.day
                        item(key = "day-${e.day}") { DayHeader(e) }
                    }
                    item(key = e.id) {
                        EditionRow(
                            e,
                            downloads[e.id],
                            onPlay = { activity.play(editions, index) },
                            onDownload = { Downloads.start(activity, e) },
                            onRemove = { Downloads.remove(activity, e.id) },
                        )
                    }
                }
                if (editions.isEmpty() && !loading && error == null) {
                    item {
                        Text(
                            stringResource(R.string.empty),
                            color = Palette.Mist,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showSettings)
        SettingsDialog(
            activity,
            settings,
            onClose = {
                showSettings = false
                refresh()
            },
        )
}

@Composable
private fun stringResource(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)

@Composable
private fun DayHeader(e: Edition) {
    val today = java.time.LocalDate.now(Edition.BERLIN)
    val date = e.date.atZoneSameInstant(Edition.BERLIN).toLocalDate()
    val label =
        when (date) {
            today -> "Heute"
            today.minusDays(1) -> "Gestern"
            else -> e.day
        }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
        Text(label, color = Palette.Mist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Palette.Mist.copy(alpha = 0.22f))
    }
}

@Composable
private fun EditionRow(
    e: Edition,
    download: Download?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            e.time,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(104.dp),
        )
        Column(Modifier.weight(1f)) {
            if (e.live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Signal))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.live_from_start),
                        color = Palette.Ink,
                        fontSize = 15.sp,
                    )
                }
            } else {
                Text(
                    when (download?.state) {
                        Download.STATE_COMPLETED -> stringResource(R.string.downloaded)
                        Download.STATE_DOWNLOADING ->
                            stringResource(
                                R.string.downloading,
                                download.percentDownloaded.toInt().coerceAtLeast(0),
                            )
                        Download.STATE_QUEUED -> stringResource(R.string.queued)
                        Download.STATE_FAILED -> stringResource(R.string.download_failed)
                        else -> "tagesschau"
                    },
                    color =
                        if (download?.state == Download.STATE_COMPLETED) Palette.Signal
                        else Palette.Mist,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!e.live) {
            when (download?.state) {
                Download.STATE_DOWNLOADING,
                Download.STATE_QUEUED ->
                    IconButton(onClick = onRemove) {
                        CircularProgressIndicator(
                            progress = { (download.percentDownloaded / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                Download.STATE_COMPLETED ->
                    IconButton(onClick = onRemove) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            stringResource(R.string.delete_download),
                            tint = Palette.Mist,
                        )
                    }
                else ->
                    IconButton(onClick = onDownload) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            stringResource(R.string.download),
                            tint = Palette.Ink,
                        )
                    }
            }
        }
    }
}

@Composable
private fun SettingsDialog(activity: MainActivity, settings: Settings, onClose: () -> Unit) {
    var server by remember { mutableStateOf(settings.serverUrl) }
    var retention by remember { mutableStateOf(settings.retentionHours.toString()) }
    var preferLive by remember { mutableStateOf(settings.preferLive) }
    var leaveMode by remember { mutableStateOf(settings.leaveMode) }
    val shizuku = remember { ShizukuInstaller.get(activity) }
    val shizukuState by shizuku.state.collectAsState()
    LaunchedEffect(Unit) { shizuku.refresh() }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Deep,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    server,
                    { server = it },
                    label = { Text(stringResource(R.string.server)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    retention,
                    { retention = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.retention_hours)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.prefer_live), Modifier.weight(1f))
                    Switch(preferLive, { preferLive = it })
                }
                Text(stringResource(R.string.leave_mode), fontWeight = FontWeight.SemiBold)
                LeaveMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { leaveMode = mode },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = leaveMode == mode, onClick = { leaveMode = mode })
                        Text(
                            stringResource(
                                when (mode) {
                                    LeaveMode.PIP -> R.string.leave_pip
                                    LeaveMode.AUDIO -> R.string.leave_audio
                                    LeaveMode.STOP -> R.string.leave_stop
                                }
                            )
                        )
                    }
                }
                HorizontalDivider(color = Palette.Mist.copy(alpha = 0.22f))
                Text(stringResource(R.string.updates), fontWeight = FontWeight.SemiBold)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_COMMIT.take(7)})",
                    color = Palette.Mist,
                    fontSize = 13.sp,
                )
                Text(
                    stringResource(
                        when (shizukuState) {
                            ShizukuState.READY -> R.string.shizuku_ready
                            ShizukuState.PERMISSION_NEEDED -> R.string.shizuku_permission_needed
                            ShizukuState.PERMISSION_DENIED -> R.string.shizuku_permission_denied
                            ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
                            ShizukuState.UNAVAILABLE -> R.string.shizuku_unavailable
                        }
                    ),
                    color = Palette.Mist,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shizukuState == ShizukuState.PERMISSION_NEEDED) {
                        TextButton(onClick = { shizuku.requestPermission() }) {
                            Text(stringResource(R.string.shizuku_allow))
                        }
                    }
                    TextButton(onClick = { activity.installUpdate() }) {
                        Text(stringResource(R.string.update_check))
                    }
                }
                HorizontalDivider(color = Palette.Mist.copy(alpha = 0.22f))
                Text(
                    stringResource(R.string.tasker_overlay_hint),
                    color = Palette.Mist,
                    fontSize = 13.sp,
                )
                TextButton(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${activity.packageName}".toUri(),
                            )
                        )
                    }
                ) {
                    Text(
                        stringResource(
                            if (AndroidSettings.canDrawOverlays(activity))
                                R.string.tasker_overlay_granted
                            else R.string.tasker_overlay_grant
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (server.startsWith("http")) settings.serverUrl = server
                    retention.toIntOrNull()?.let { settings.retentionHours = it }
                    settings.preferLive = preferLive
                    settings.leaveMode = leaveMode
                    Downloads.prune(activity)
                    onClose()
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
