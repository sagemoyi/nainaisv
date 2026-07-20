package com.xmoyi.nainaisv.player

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.xmoyi.nainaisv.data.DramaEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull

private const val CAREGIVER_HOLD_MS = 2_500L

@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun GrandmaScreen(
    onOpenCaregiver: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val pagerState = rememberPagerState(initialPage = state.currentIndex) { state.queue.size }

    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.onShown()
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(viewModel::onPageSelected)
    }
    LaunchedEffect(state.currentIndex, state.queue.size) {
        if (state.queue.isNotEmpty() &&
            pagerState.currentPage != state.currentIndex &&
            !pagerState.isScrollInProgress
        ) {
            pagerState.animateScrollToPage(state.currentIndex)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.resume()
                Lifecycle.Event.ON_PAUSE -> viewModel.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2_000) {
            viewModel.pause()
            activity?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.queue.isNotEmpty() -> {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page -> state.queue[page].id },
                ) { page ->
                    val item = state.queue[page]
                    DramaPage(
                        item = item,
                        isCurrent = page == state.currentIndex,
                        state = state,
                        onTap = {
                            viewModel.togglePlayback()
                            viewModel.dismissHint()
                        },
                    )
                }
            }
            state.loading -> LoadingMessage("正在准备故事…")
            else -> LoadingMessage("还没有可以看的故事\n请家人添加内容")
        }

        if (state.queue.isNotEmpty() && !state.isPlaying && !state.isBuffering && state.errorMessage == null) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "播放",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(110.dp).align(Alignment.Center),
            )
        }
        if (state.isBuffering && state.errorMessage == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
        }

        AnimatedVisibility(
            visible = state.showHint,
            modifier = Modifier.align(Alignment.Center),
            exit = fadeOut(),
        ) {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.large).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.PauseCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                Text("点一下暂停或继续", color = Color.White, fontSize = 20.sp)
                Text("上滑换一个故事", color = Color.White, fontSize = 20.sp)
            }
        }

        state.errorMessage?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(message, color = Color.White, fontSize = 22.sp)
                Button(onClick = viewModel::retry) { Text("再试一次", fontSize = 20.sp) }
            }
        }

        // 隐藏的家属入口：按住左上角约三秒。不消费普通点击和滑动。
        Box(
            Modifier
                .size(96.dp)
                .align(Alignment.TopStart)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val downAt = System.currentTimeMillis()
                        val up = withTimeoutOrNull(CAREGIVER_HOLD_MS) { waitForUpOrCancellation() }
                        if (up == null && System.currentTimeMillis() - downAt >= CAREGIVER_HOLD_MS) {
                            viewModel.pause()
                            onOpenCaregiver()
                            // 消费剩余事件直到抬起，避免触发底层的单击暂停/继续。
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                },
        )
    }
}

@UnstableApi
@Composable
private fun DramaPage(
    item: DramaEntity,
    isCurrent: Boolean,
    state: PlayerUiState,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onTap() })
            },
    ) {
        if (isCurrent && state.player != null) {
            AndroidView(
                factory = { playerContext ->
                    PlayerView(playerContext).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        keepScreenOn = true
                    }
                },
                update = { playerView ->
                    playerView.player = state.player
                    playerView.resizeMode = if (item.height > item.width) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 封面：非当前页一直显示；当前页在第一帧出来前显示，避免黑屏。
        val showCover = !isCurrent || state.player == null || (state.isBuffering && state.positionMs <= 0)
        if (showCover && item.coverUrl.isNotBlank()) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                item.seriesTitle.ifBlank { item.title },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isCurrent && state.episodeLabel != null) {
                    Text(state.episodeLabel, color = Color.White, fontSize = 19.sp)
                }
                Text(item.ownerName, color = Color.White.copy(alpha = 0.75f), fontSize = 19.sp)
            }
            if (isCurrent) {
                LinearProgressIndicator(
                    progress = {
                        if (state.durationMs > 0) {
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
        }
    }
}

@Composable
private fun LoadingMessage(message: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(18.dp))
        Text(message, color = Color.White, fontSize = 24.sp, lineHeight = 34.sp)
    }
}
