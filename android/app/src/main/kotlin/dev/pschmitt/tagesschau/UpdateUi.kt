package dev.pschmitt.tagesschau

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pschmitt.tagesschau.update.UpdateState

/** Pull down to check for updates (About page only); other pages are left alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToCheck(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = { content() },
        )
    } else {
        Box(modifier) { content() }
    }
}

/**
 * "Check for updates" on the About page: the icon spins and a bar runs while searching; a found
 * update turns the entry orange with an install button; a download shows its progress.
 */
@Composable
fun UpdateEntry(
    state: UpdateState,
    version: String,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    colors: androidx.compose.material3.ListItemColors,
) {
    val spinning = state is UpdateState.Checking
    val angle by
        rememberInfiniteTransition(label = "update-spin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                label = "angle",
            )
    val found =
        when (state) {
            is UpdateState.Available -> state.update
            is UpdateState.Downloading -> state.update
            is UpdateState.Installing -> state.update
            else -> null
        }
    Column(
        Modifier.fillMaxWidth()
            .background(if (found != null) Palette.Signal.copy(0.22f) else Color.Transparent)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    stringResource(
                        when (state) {
                            is UpdateState.Checking -> R.string.update_checking
                            is UpdateState.Available,
                            is UpdateState.Downloading,
                            is UpdateState.Installing -> R.string.update_available
                            is UpdateState.UpToDate -> R.string.update_none
                            is UpdateState.Failed -> R.string.update_failed
                            else -> R.string.update_check
                        }
                    ),
                    color = if (found != null) Palette.Signal else Color.Unspecified,
                )
            },
            supportingContent = {
                Text(
                    when (state) {
                        is UpdateState.Available ->
                            stringResource(R.string.update_found_text, state.update.commit.take(7))
                        is UpdateState.Downloading ->
                            if (state.progress >= 0)
                                stringResource(
                                    R.string.update_downloading,
                                    (state.progress * 100).toInt(),
                                )
                            else stringResource(R.string.update_installing)
                        is UpdateState.Installing -> stringResource(R.string.update_installing)
                        is UpdateState.Checking -> version
                        else -> "$version\n" + stringResource(R.string.update_pull_hint)
                    }
                )
            },
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_update),
                    null,
                    tint = if (found != null) Palette.Signal else Palette.Ink,
                    modifier = Modifier.rotate(if (spinning) angle else 0f),
                )
            },
            trailingContent = {
                if (state is UpdateState.Available) {
                    FilledTonalButton(onClick = onInstall) {
                        Text(stringResource(R.string.update_install))
                    }
                }
            },
            modifier =
                Modifier.clickable(
                    enabled =
                        state !is UpdateState.Checking &&
                            state !is UpdateState.Downloading &&
                            state !is UpdateState.Installing
                ) {
                    if (state is UpdateState.Available) onInstall() else onCheck()
                },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        when {
            state is UpdateState.Checking ||
                state is UpdateState.Installing ||
                (state is UpdateState.Downloading && state.progress < 0) ->
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = Palette.Signal,
                    trackColor = Palette.Deep,
                )
            state is UpdateState.Downloading ->
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = Palette.Signal,
                    trackColor = Palette.Deep,
                )
        }
    }
}
