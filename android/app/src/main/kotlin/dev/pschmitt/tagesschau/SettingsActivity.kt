package dev.pschmitt.tagesschau

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pschmitt.tagesschau.update.ShizukuInstaller
import dev.pschmitt.tagesschau.update.ShizukuState
import dev.pschmitt.tagesschau.update.Updater
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TagesschauTheme { SettingsScreen(this) } }
    }
}

/** The settings' pages: an overview, then one page per topic. */
private enum class Page(val title: Int) {
    ROOT(R.string.settings),
    PLAYBACK(R.string.section_playback),
    DOWNLOADS(R.string.section_downloads),
    NOTIFICATIONS(R.string.section_notifications),
    SERVER(R.string.section_server),
    ADVANCED(R.string.section_advanced),
    ABOUT(R.string.section_about),
}

private val RETENTION =
    listOf(
        24 to R.string.retention_1d,
        48 to R.string.retention_2d,
        72 to R.string.retention_3d,
        168 to R.string.retention_7d,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: SettingsActivity) {
    val settings = remember { Settings(activity) }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var preferLive by remember { mutableStateOf(settings.preferLive) }
    var leaveMode by remember { mutableStateOf(settings.leaveMode) }
    var retention by remember { mutableStateOf(settings.retentionHours) }
    var autoNext by remember { mutableStateOf(settings.autoNext) }
    var subtitles by remember { mutableStateOf(settings.subtitles) }
    var clockFormat by remember { mutableStateOf(settings.clockFormat) }
    var autoPlay by remember { mutableStateOf(settings.autoPlay) }
    var notifyNew by remember { mutableStateOf(settings.notifyNew) }
    var btDevices by remember { mutableStateOf(settings.bluetoothDevices) }
    var btMode by remember { mutableStateOf(settings.bluetoothMode) }
    var btDelay by remember { mutableStateOf(settings.bluetoothDelaySeconds) }
    var btResume by remember { mutableStateOf(settings.bluetoothResume) }
    var continueOnDisconnect by remember { mutableStateOf(settings.continueOnDisconnect) }
    var btVolumeOn by remember { mutableStateOf(settings.bluetoothVolume >= 0) }
    var btVolumeLevel by remember {
        mutableStateOf(settings.bluetoothVolume.takeIf { it >= 0 } ?: settings.bluetoothVolumeLevel)
    }
    var choosingBt by remember { mutableStateOf(false) }
    val btPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) choosingBt = true
        }
    var autoDownload by remember { mutableStateOf(settings.autoDownload) }
    var autoCharging by remember { mutableStateOf(settings.autoDownloadCharging) }
    var quality by remember { mutableStateOf(settings.downloadQuality) }
    var server by remember { mutableStateOf(settings.serverUrl) }
    var editServer by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(AndroidSettings.canDrawOverlays(activity)) }
    val shizuku = remember { ShizukuInstaller.get(activity) }
    val shizukuState by shizuku.state.collectAsState()
    LaunchedEffect(Unit) { shizuku.refresh() }
    LifecycleResumeEffect(Unit) {
        overlay = AndroidSettings.canDrawOverlays(activity)
        shizuku.refresh()
        onPauseOrDispose {}
    }
    val itemColors = ListItemDefaults.colors(containerColor = Palette.Studio)
    var page by remember { mutableStateOf(Page.ROOT) }
    androidx.activity.compose.BackHandler(enabled = page != Page.ROOT) { page = Page.ROOT }

    /** A row on the overview that opens a page. */
    @Composable
    fun PageLink(title: Int, help: Int, icon: Int, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(help)) },
            leadingContent = { Icon(painterResource(icon), null) },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron), null) },
            modifier = Modifier.clickable(onClick = onClick),
            colors = itemColors,
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Palette.Studio,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (page == Page.ROOT) activity.finish() else page = Page.ROOT }
                    ) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Palette.Studio,
                        scrolledContainerColor = Palette.Deep,
                    ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            when (page) {
                Page.ROOT -> {
                    PageLink(
                        R.string.section_playback,
                        R.string.page_playback_help,
                        R.drawable.ic_play,
                    ) {
                        page = Page.PLAYBACK
                    }
                    PageLink(
                        R.string.section_downloads,
                        R.string.page_downloads_help,
                        R.drawable.ic_download,
                    ) {
                        page = Page.DOWNLOADS
                    }
                    PageLink(
                        R.string.section_notifications,
                        R.string.page_notifications_help,
                        R.drawable.ic_bell,
                    ) {
                        page = Page.NOTIFICATIONS
                    }
                    PageLink(
                        R.string.section_server,
                        R.string.page_server_help,
                        R.drawable.ic_server,
                    ) {
                        page = Page.SERVER
                    }
                    PageLink(
                        R.string.section_advanced,
                        R.string.page_advanced_help,
                        R.drawable.ic_bolt,
                    ) {
                        page = Page.ADVANCED
                    }
                    PageLink(R.string.section_about, R.string.page_about_help, R.drawable.ic_info) {
                        page = Page.ABOUT
                    }
                }
                Page.PLAYBACK -> {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.auto_play)) },
                        supportingContent = { Text(stringResource(R.string.auto_play_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_ctl_play), null) },
                        colors = itemColors,
                    )
                    AutoPlay.entries.forEach { mode ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            AutoPlay.VIDEO_SOUND -> R.string.auto_play_both
                                            AutoPlay.VIDEO_ONLY -> R.string.auto_play_video
                                            AutoPlay.SOUND_ONLY -> R.string.auto_play_sound
                                            AutoPlay.NONE -> R.string.auto_play_none
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                RadioButton(selected = autoPlay == mode, onClick = null)
                            },
                            modifier =
                                Modifier.padding(start = 40.dp).selectable(
                                    selected = autoPlay == mode,
                                    role = Role.RadioButton,
                                ) {
                                    autoPlay = mode
                                    settings.autoPlay = mode
                                },
                            colors = itemColors,
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.prefer_live)) },
                        supportingContent = { Text(stringResource(R.string.prefer_live_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_play), null) },
                        trailingContent = {
                            Switch(
                                preferLive,
                                {
                                    preferLive = it
                                    settings.preferLive = it
                                },
                            )
                        },
                        colors = itemColors,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clock_format)) },
                        supportingContent = { Text(stringResource(R.string.clock_format_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_clock), null) },
                        colors = itemColors,
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        val formats =
                            listOf(
                                ClockFormat.OFF to "Aus",
                                ClockFormat.MINUTES to "20:00",
                                ClockFormat.SECONDS to "20:00:06",
                                ClockFormat.MILLIS to ",123",
                            )
                        formats.forEachIndexed { i, (f, label) ->
                            SegmentedButton(
                                selected = clockFormat == f,
                                onClick = {
                                    clockFormat = f
                                    settings.clockFormat = f
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, formats.size),
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.subtitles)) },
                        supportingContent = { Text(stringResource(R.string.subtitles_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_cc), null) },
                        trailingContent = {
                            Switch(
                                subtitles,
                                {
                                    subtitles = it
                                    settings.subtitles = it
                                },
                            )
                        },
                        colors = itemColors,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.auto_next)) },
                        supportingContent = { Text(stringResource(R.string.auto_next_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_ctl_next), null) },
                        trailingContent = {
                            Switch(
                                autoNext,
                                {
                                    autoNext = it
                                    settings.autoNext = it
                                },
                            )
                        },
                        colors = itemColors,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.leave_mode)) },
                        supportingContent = { Text(stringResource(R.string.leave_mode_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_pip), null) },
                        colors = itemColors,
                    )
                    LeaveMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            LeaveMode.PIP -> R.string.leave_pip
                                            LeaveMode.AUDIO -> R.string.leave_audio
                                            LeaveMode.STOP -> R.string.leave_stop
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                RadioButton(selected = leaveMode == mode, onClick = null)
                            },
                            modifier =
                                Modifier.padding(start = 40.dp).selectable(
                                    selected = leaveMode == mode,
                                    role = Role.RadioButton,
                                ) {
                                    leaveMode = mode
                                    settings.leaveMode = mode
                                },
                            colors = itemColors,
                        )
                    }
                }
                Page.DOWNLOADS -> {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.auto_download)) },
                        supportingContent = { Text(stringResource(R.string.auto_download_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_download), null) },
                        colors = itemColors,
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        val modes =
                            listOf(
                                AutoDownload.OFF to R.string.auto_off,
                                AutoDownload.WIFI to R.string.auto_wifi,
                                AutoDownload.ALWAYS to R.string.auto_always,
                            )
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = autoDownload == mode,
                                onClick = {
                                    autoDownload = mode
                                    settings.autoDownload = mode
                                    Downloads.scheduleAutoDownload(activity)
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) {
                                Text(stringResource(label), fontSize = 13.sp)
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.auto_charging)) },
                        supportingContent = { Text(stringResource(R.string.auto_charging_help)) },
                        trailingContent = {
                            Switch(
                                autoCharging,
                                {
                                    autoCharging = it
                                    settings.autoDownloadCharging = it
                                    Downloads.scheduleAutoDownload(activity)
                                },
                                enabled = autoDownload != AutoDownload.OFF,
                            )
                        },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.download_quality)) },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        val qualities =
                            listOf("webxxl" to "1080p", "webxl" to "720p", "webm" to "540p")
                        qualities.forEachIndexed { i, (q, label) ->
                            SegmentedButton(
                                selected = quality == q,
                                onClick = {
                                    quality = q
                                    settings.downloadQuality = q
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, qualities.size),
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.retention)) },
                        supportingContent = { Text(stringResource(R.string.retention_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_download), null) },
                        colors = itemColors,
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        RETENTION.forEachIndexed { i, (hours, label) ->
                            SegmentedButton(
                                selected = retention == hours,
                                onClick = {
                                    retention = hours
                                    settings.retentionHours = hours
                                    Downloads.prune(activity)
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, RETENTION.size),
                            ) {
                                Text(stringResource(label), fontSize = 13.sp)
                            }
                        }
                    }
                }
                Page.NOTIFICATIONS -> {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notify_new)) },
                        supportingContent = { Text(stringResource(R.string.notify_new_help)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_bell), null) },
                        trailingContent = {
                            Switch(
                                notifyNew,
                                {
                                    notifyNew = it
                                    settings.notifyNew = it
                                    EditionWatch.schedule(activity)
                                },
                            )
                        },
                        colors = itemColors,
                    )
                }
                Page.SERVER -> {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.server)) },
                        supportingContent = { Text(server) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_server), null) },
                        modifier = Modifier.clickable { editServer = true },
                        colors = itemColors,
                    )
                }
                Page.ADVANCED -> {
                    Section(R.string.section_bluetooth)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bt_autoplay)) },
                        supportingContent = {
                            Text(
                                if (btDevices.isEmpty()) stringResource(R.string.bt_autoplay_none)
                                else
                                    stringResource(
                                        R.string.bt_autoplay_some,
                                        btDevices.size,
                                        bondedDevices(activity)
                                            .filter { it.first in btDevices }
                                            .joinToString { it.second },
                                    )
                            )
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_bluetooth), null) },
                        modifier =
                            Modifier.clickable {
                                if (
                                    android.os.Build.VERSION.SDK_INT >= 31 &&
                                        activity.checkSelfPermission(
                                            android.Manifest.permission.BLUETOOTH_CONNECT
                                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    btPermission.launch(
                                        android.Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                } else {
                                    choosingBt = true
                                }
                            },
                        colors = itemColors,
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 4.dp)
                    ) {
                        val modes =
                            listOf(
                                BluetoothMode.AUDIO to R.string.bt_mode_audio,
                                BluetoothMode.VIDEO to R.string.bt_mode_video,
                                BluetoothMode.PIP to R.string.bt_mode_pip,
                            )
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = btMode == mode,
                                onClick = {
                                    btMode = mode
                                    settings.bluetoothMode = mode
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) {
                                Text(stringResource(label), fontSize = 13.sp)
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bt_volume)) },
                        supportingContent = {
                            Text(
                                if (btVolumeOn)
                                    stringResource(R.string.bt_volume_value, btVolumeLevel)
                                else stringResource(R.string.bt_volume_off)
                            )
                        },
                        trailingContent = {
                            Switch(
                                btVolumeOn,
                                {
                                    btVolumeOn = it
                                    settings.bluetoothVolume = if (it) btVolumeLevel else -1
                                },
                            )
                        },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    androidx.compose.material3.Slider(
                        value = btVolumeLevel.toFloat(),
                        onValueChange = {
                            btVolumeLevel = it.toInt()
                            settings.bluetoothVolumeLevel = btVolumeLevel
                            if (btVolumeOn) settings.bluetoothVolume = btVolumeLevel
                        },
                        valueRange = 0f..100f,
                        enabled = btVolumeOn,
                        modifier = Modifier.padding(start = 72.dp, end = 24.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.continue_on_disconnect)) },
                        supportingContent = {
                            Text(stringResource(R.string.continue_on_disconnect_help))
                        },
                        trailingContent = {
                            Switch(
                                continueOnDisconnect,
                                {
                                    continueOnDisconnect = it
                                    settings.continueOnDisconnect = it
                                },
                            )
                        },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bt_resume)) },
                        supportingContent = { Text(stringResource(R.string.bt_resume_help)) },
                        trailingContent = {
                            Switch(
                                btResume,
                                {
                                    btResume = it
                                    settings.bluetoothResume = it
                                },
                            )
                        },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bt_delay)) },
                        colors = itemColors,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 4.dp)
                    ) {
                        val delays = listOf(0, 5, 10, 30)
                        delays.forEachIndexed { i, sec ->
                            SegmentedButton(
                                selected = btDelay == sec,
                                onClick = {
                                    btDelay = sec
                                    settings.bluetoothDelaySeconds = sec
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, delays.size),
                            ) {
                                Text("$sec s", fontSize = 13.sp)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.bt_hint),
                        color = Palette.Mist,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                    )

                    Section(R.string.section_tasker)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.tasker_overlay)) },
                        supportingContent = { Text(stringResource(R.string.tasker_overlay_hint)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_bolt), null) },
                        trailingContent = {
                            Switch(
                                overlay,
                                {
                                    activity.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            "package:${activity.packageName}".toUri(),
                                        )
                                    )
                                },
                            )
                        },
                        colors = itemColors,
                    )
                }
                Page.ABOUT -> {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.update_check)) },
                        supportingContent = {
                            Text(
                                "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_COMMIT.take(7)})"
                            )
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_update), null) },
                        modifier =
                            Modifier.clickable {
                                scope.launch {
                                    val update = runCatching { Updater.check() }.getOrNull()
                                    if (update == null) {
                                        toast(activity, R.string.update_none)
                                    } else {
                                        toast(activity, R.string.update_installing)
                                        if (
                                            !runCatching { Updater.install(activity, update) }
                                                .getOrDefault(false)
                                        ) {
                                            toast(activity, R.string.update_failed)
                                        }
                                    }
                                }
                            },
                        colors = itemColors,
                    )
                    ListItem(
                        headlineContent = { Text("Shizuku") },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when (shizukuState) {
                                        ShizukuState.READY -> R.string.shizuku_ready
                                        ShizukuState.PERMISSION_NEEDED ->
                                            R.string.shizuku_permission_needed
                                        ShizukuState.PERMISSION_DENIED ->
                                            R.string.shizuku_permission_denied
                                        ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
                                        ShizukuState.UNAVAILABLE -> R.string.shizuku_unavailable
                                    }
                                )
                            )
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_shield), null) },
                        trailingContent = {
                            when (shizukuState) {
                                ShizukuState.PERMISSION_NEEDED ->
                                    FilledTonalButton(onClick = { shizuku.requestPermission() }) {
                                        Text(stringResource(R.string.allow))
                                    }
                                ShizukuState.NOT_RUNNING,
                                ShizukuState.PERMISSION_DENIED ->
                                    shizuku.managerLaunchIntent()?.let { open ->
                                        FilledTonalButton(
                                            onClick = { activity.startActivity(open) }
                                        ) {
                                            Text(stringResource(R.string.open))
                                        }
                                    }
                                else -> Unit
                            }
                        },
                        colors = itemColors,
                    )

                    ListItem(
                        headlineContent = { Text("GitHub") },
                        supportingContent = { Text("github.com/pschmitt/tagesschau") },
                        leadingContent = { Icon(painterResource(R.drawable.ic_info), null) },
                        modifier =
                            Modifier.clickable {
                                activity.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://github.com/pschmitt/tagesschau".toUri(),
                                    )
                                )
                            },
                        colors = itemColors,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.web_player)) },
                        supportingContent = { Text(server) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_play), null) },
                        modifier =
                            Modifier.clickable {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, server.toUri()))
                            },
                        colors = itemColors,
                    )
                    Text(
                        stringResource(R.string.disclaimer),
                        color = Palette.Mist,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (choosingBt) {
        val devices = remember { bondedDevices(activity) }
        var picked by remember { mutableStateOf(btDevices) }
        AlertDialog(
            onDismissRequest = { choosingBt = false },
            containerColor = Palette.Deep,
            title = { Text(stringResource(R.string.bt_choose)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (devices.isEmpty()) Text(stringResource(R.string.bt_no_devices))
                    devices.forEach { (address, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(address, fontSize = 12.sp) },
                            leadingContent = {
                                androidx.compose.material3.Checkbox(
                                    checked = address in picked,
                                    onCheckedChange = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    picked =
                                        if (address in picked) picked - address
                                        else picked + address
                                },
                            colors = ListItemDefaults.colors(containerColor = Palette.Deep),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.bluetoothDevices = picked
                        btDevices = picked
                        choosingBt = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { choosingBt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (editServer) {
        var draft by remember { mutableStateOf(server) }
        AlertDialog(
            onDismissRequest = { editServer = false },
            containerColor = Palette.Deep,
            title = { Text(stringResource(R.string.server)) },
            text = { OutlinedTextField(draft, { draft = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (draft.startsWith("http")) {
                            settings.serverUrl = draft
                            server = settings.serverUrl
                        }
                        editServer = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editServer = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        color = Palette.Signal,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

private fun toast(activity: SettingsActivity, id: Int) =
    Toast.makeText(activity, activity.getString(id), Toast.LENGTH_LONG).show()

/** Paired Bluetooth devices as (address, name); empty without the permission. */
@android.annotation.SuppressLint("MissingPermission")
private fun bondedDevices(context: android.content.Context): List<Pair<String, String>> =
    runCatching {
            context
                .getSystemService(android.bluetooth.BluetoothManager::class.java)
                .adapter
                ?.bondedDevices
                .orEmpty()
                .map { it.address to (it.name ?: it.address) }
                .sortedBy { it.second.lowercase() }
        }
        .getOrDefault(emptyList())
