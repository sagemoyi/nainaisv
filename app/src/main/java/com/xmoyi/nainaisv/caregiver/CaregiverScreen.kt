package com.xmoyi.nainaisv.caregiver

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xmoyi.nainaisv.BuildConfig
import com.xmoyi.nainaisv.data.CreatorEntity
import com.xmoyi.nainaisv.data.DramaEntity
import com.xmoyi.nainaisv.update.UpdateState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaregiverScreen(
    onboarding: Boolean,
    onExit: () -> Unit,
    viewModel: CaregiverViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (onboarding) {
        OnboardingContent(state, viewModel, snackbar, onExit)
    } else {
        ManagementContent(state, viewModel, snackbar, onExit)
    }
}

// ---------- 首次设置：两步引导 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingContent(
    state: CaregiverUiState,
    viewModel: CaregiverViewModel,
    snackbar: SnackbarHostState,
    onExit: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (step == 0) "首次设置 1/2 · 家属 PIN" else "首次设置 2/2 · 添加内容") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (step) {
            0 -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "这台手机将来交给老人时，只会看到全屏播放的界面。" +
                        "家属 PIN 用来打开这个管理页面，请设置一个只有家人知道的数字密码。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PinEditor(state, viewModel)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { step = 1 },
                    enabled = state.hasPin,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.hasPin) "下一步：添加内容" else "先保存 PIN 才能继续") }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                ContentTab(
                    state = state,
                    viewModel = viewModel,
                    headerText = "粘贴 B 站链接，或搜索关键词，然后“信任”喜欢的作者。" +
                        "已信任 ${state.trustedCreators.size} 位作者。建议先看过对方几部作品再信任。",
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { step = 0 }) { Text("上一步") }
                    Button(
                        onClick = { viewModel.finishSetup(onExit) },
                        enabled = !state.busy && state.trustedCreators.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("完成设置，进入奶奶模式")
                    }
                }
            }
        }
    }
}

// ---------- 日常管理：页签 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagementContent(
    state: CaregiverUiState,
    viewModel: CaregiverViewModel,
    snackbar: SnackbarHostState,
    onExit: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("内容", "作者", "历史", "设置")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("家属管理") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回看剧")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> ContentTab(state, viewModel, headerText = null, modifier = Modifier.weight(1f))
                1 -> CreatorsTab(state, viewModel, Modifier.weight(1f))
                2 -> HistoryTab(state, Modifier.weight(1f))
                3 -> SettingsTab(state, viewModel, Modifier.weight(1f))
            }
        }
    }
}

// ---------- 内容页签（引导第 2 步复用） ----------

@Composable
private fun ContentTab(
    state: CaregiverUiState,
    viewModel: CaregiverViewModel,
    headerText: String?,
    modifier: Modifier = Modifier,
) {
    var keyword by remember { mutableStateOf("AI短剧 全集") }
    var link by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        headerText?.let {
            item { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        item {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("粘贴 B 站视频、合集或 UP 主空间链接") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { viewModel.importLink(link); link = "" },
                enabled = !state.busy && link.isNotBlank(),
            ) { Text("导入并信任作者") }
        }
        item {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索候选内容") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.search(keyword) }, enabled = !state.busy) { Text("搜索") }
                if (state.busy || state.syncing) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        item { HorizontalDivider() }
        item {
            Text(
                "候选内容（未知作者，需要家属确认）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.candidates.isEmpty()) {
            item { Text("还没有候选内容。可以搜索或粘贴链接。") }
        }
        items(state.candidates.size) { index ->
            val item = state.candidates[index]
            CandidateCard(
                item = item,
                busy = state.busy,
                onTrust = { viewModel.trust(item) },
                onBlock = { viewModel.block(item) },
            )
        }
    }
}

@Composable
private fun CandidateCard(
    item: DramaEntity,
    busy: Boolean,
    onTrust: () -> Unit,
    onBlock: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(110.dp)
                        .height(70.dp)
                        .align(Alignment.CenterVertically),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(item.ownerName, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTrust, enabled = !busy) { Text("信任作者") }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, "https://www.bilibili.com/video/${item.bvid}".toUri()),
                            )
                        }
                    }) { Text("预览") }
                    IconButton(onClick = onBlock, enabled = !busy) {
                        Icon(Icons.Default.Block, contentDescription = "屏蔽作者")
                    }
                }
            }
        }
    }
}

// ---------- 作者页签 ----------

@Composable
private fun CreatorsTab(
    state: CaregiverUiState,
    viewModel: CaregiverViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "可信作者（${state.trustedCreators.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::refreshAll, enabled = !state.busy) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("刷新作品")
                }
            }
        }
        if (state.trustedCreators.isEmpty()) {
            item { Text("尚未添加可信作者。到“内容”页搜索或粘贴链接。") }
        }
        items(state.trustedCreators.size) { index ->
            val creator = state.trustedCreators[index]
            CreatorCard(
                creator = creator,
                primaryLabel = "移出可信",
                onPrimary = { viewModel.removeTrust(creator) },
                onBlock = { viewModel.block(creator) },
            )
        }
        if (state.blockedCreators.isNotEmpty()) {
            item { HorizontalDivider() }
            item {
                Text(
                    "已屏蔽作者",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(state.blockedCreators.size) { index ->
                val creator = state.blockedCreators[index]
                CreatorCard(
                    creator = creator,
                    primaryLabel = "解除屏蔽",
                    onPrimary = { viewModel.unblock(creator) },
                    onBlock = null,
                )
            }
        }
    }
}

@Composable
private fun CreatorCard(
    creator: CreatorEntity,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBlock: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(creator.name, style = MaterialTheme.typography.titleMedium)
                Text("UID ${creator.mid}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPrimary) { Text(primaryLabel) }
            if (onBlock != null) {
                IconButton(onClick = onBlock) { Icon(Icons.Default.Block, contentDescription = "屏蔽") }
            }
        }
    }
}

// ---------- 历史页签 ----------

@Composable
private fun HistoryTab(state: CaregiverUiState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.history.isEmpty()) {
            item { Text("还没有观看记录。") }
        }
        items(state.history.size) { index ->
            val entry = state.history[index]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.drama.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = entry.drama.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .height(60.dp)
                                .align(Alignment.CenterVertically),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.drama.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "看到 ${(entry.watch.completion * 100).toInt()}% · " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(entry.watch.lastWatchedAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ---------- 设置页签 ----------

@Composable
private fun SettingsTab(
    state: CaregiverUiState,
    viewModel: CaregiverViewModel,
    modifier: Modifier = Modifier,
) {
    var positive by remember(state.positiveTerms) { mutableStateOf(state.positiveTerms) }
    var blocked by remember(state.blockedTerms) { mutableStateOf(state.blockedTerms) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("家属 PIN", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        PinEditor(state, viewModel)

        HorizontalDivider()
        Text("内容过滤", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = positive,
            onValueChange = { positive = it },
            label = { Text("优先词，用逗号分隔") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = blocked,
            onValueChange = { blocked = it },
            label = { Text("过滤词，用逗号分隔") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.saveTerms(positive, blocked) }, enabled = !state.busy) {
            Text("保存过滤规则")
        }

        HorizontalDivider()
        Text("应用更新", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        UpdatePanel(state.updateState, viewModel)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PinEditor(state: CaregiverUiState, viewModel: CaregiverViewModel) {
    var pin by remember { mutableStateOf("") }
    Text(if (state.hasPin) "PIN 已设置，输入新 PIN 可以覆盖。" else "PIN 用于打开这个管理页面。")
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
        label = { Text("至少四位数字") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { viewModel.savePin(pin); pin = "" },
        enabled = !state.busy && pin.length >= 4,
    ) { Text("保存 PIN") }
}

@Composable
private fun UpdatePanel(state: UpdateState, viewModel: CaregiverViewModel) {
    when (state) {
        UpdateState.Idle -> Button(onClick = viewModel::checkUpdate) { Text("检查更新") }
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("  正在检查…")
        }
        UpdateState.UpToDate -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已经是最新版本。")
            OutlinedButton(onClick = viewModel::checkUpdate) { Text("重新检查") }
        }
        is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发现 ${state.manifest.versionName}")
            Text(state.manifest.releaseNotes.ifBlank { "新版本可用" })
            Button(onClick = { viewModel.downloadUpdate(state.manifest) }) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("下载更新")
            }
        }
        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("正在下载 ${state.manifest.versionName}：${(state.progress * 100).toInt()}%")
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.ReadyToInstall -> Button(onClick = { viewModel.installUpdate(state) }) {
            Text("安装 ${state.manifest.versionName}")
        }
        is UpdateState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = viewModel::checkUpdate) { Text("重新检查") }
        }
    }
}
