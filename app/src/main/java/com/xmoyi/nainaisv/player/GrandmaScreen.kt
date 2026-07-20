package com.xmoyi.nainaisv.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

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
    var showExitPrompt by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(true) }
    var initialPageApplied by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0) { state.queue.size }

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(viewModel::onPageSelected)
    }
    LaunchedEffect(state.currentIndex, state.queue.size) {
        if (state.queue.isEmpty()) return@LaunchedEffect
        if (!initialPageApplied) {
            // 冷启动直接落到上次看到的位置，不滑动经过中间的故事。
            initialPageApplied = true
            if (pagerState.currentPage != state.currentIndex) pagerState.scrollToPage(state.currentIndex)
        } else if (pagerState.currentPage != state.currentIndex) {
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

    // 标题和作者只在切换故事或暂停时出现几秒，不挡短剧自带的字幕。
    LaunchedEffect(state.currentIndex, state.isPlaying) {
        infoVisible = true
        if (state.isPlaying) {
            delay(4_000)
            infoVisible = false
        }
    }
    LaunchedEffect(state.showHint) {
        if (state.showHint) {
            delay(6_000)
            viewModel.dismissHint()
        }
    }
    LaunchedEffect(showExitPrompt) {
        if (showExitPrompt) {
            delay(2_000)
            showExitPrompt = false
        }
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2_000) {
            viewModel.pause()
            activity?.finish()
        } else {
            lastBackAt = now
            showExitPrompt = true
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.queue.isNotEmpty() -> {
                VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .pointerInput(page) {
                                detectTapGestures(onTap = {
                                    viewModel.togglePlayback()
                                    viewModel.dismissHint()
                                })
                            },
                    ) {
                        if (page == state.currentIndex && state.player != null) {
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
                                    // 一律等比显示，不裁掉画面边缘的台词字幕。
                                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            state.loading -> LoadingMessage("正在准备故事…")
            else -> LoadingMessage("正在找新故事…")
        }

        val currentItem = state.current ?: state.queue.getOrNull(state.currentIndex)
        AnimatedVisibility(
            visible = infoVisible && currentItem != null && state.queue.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 14.dp),
            ) {
                currentItem?.let { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.title,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(item.ownerName, color = Color.White.copy(alpha = 0.82f), fontSize = 18.sp)
                    }
                }
            }
        }

        if (state.queue.isNotEmpty()) {
            LinearProgressIndicator(
                progress = {
                    if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                color = Color.White.copy(alpha = 0.85f),
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        }

        if (state.queue.isNotEmpty() && !state.isPlaying && !state.isBuffering && state.noticeMessage == null) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "播放",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(92.dp).align(Alignment.Center),
            )
        }
        if (state.isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        AnimatedVisibility(
            visible = state.showHint,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.large).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.PauseCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                Text("点一下暂停或继续", color = Color.White, fontSize = 22.sp)
                Text("上滑看下一个故事", color = Color.White, fontSize = 22.sp)
                Text("下滑回到上一个故事", color = Color.White, fontSize = 22.sp)
            }
        }

        state.noticeMessage?.let { message ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.large)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(message, color = Color.White, fontSize = 22.sp)
            }
        }

        AnimatedVisibility(
            visible = showExitPrompt,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .background(Color.Black.copy(alpha = 0.78f), MaterialTheme.shapes.large)
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            ) {
                Text("再按一次退出", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(
            Modifier
                .size(84.dp)
                .align(Alignment.TopStart)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        viewModel.pause()
                        onOpenCaregiver()
                    },
                ),
        )
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
        Text(message, color = Color.White, fontSize = 24.sp)
    }
}
