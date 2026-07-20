package com.xmoyi.nainaisv.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var pin by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("AI短剧 全集") }
    var link by remember { mutableStateOf("") }
    var positive by remember(state.positiveTerms) { mutableStateOf(state.positiveTerms) }
    var blocked by remember(state.blockedTerms) { mutableStateOf(state.blockedTerms) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "首次设置" else "家属管理") },
                navigationIcon = {
                    if (!onboarding) {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onboarding) {
                Text("先由家人设置 PIN，并添加至少三个可信 UP 主。设置完成后，奶奶打开应用就会直接看剧。")
            }

            SectionTitle("家属 PIN")
            Text(if (state.hasPin) "PIN 已设置，可输入新 PIN 覆盖。" else "PIN 用于打开这个管理页面。")
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text("至少四位数字") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.savePin(pin); pin = "" }, enabled = !state.busy && pin.length >= 4) {
                Text("保存 PIN")
            }

            HorizontalDivider()
            SectionTitle("添加喜欢的内容")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("B站视频、合集或 UP 主空间链接") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.importLink(link); link = "" },
                enabled = !state.busy && link.isNotBlank(),
            ) { Text("导入并信任作者") }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索候选") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { viewModel.search(keyword) }, enabled = !state.busy) { Text("搜索") }
                OutlinedButton(onClick = viewModel::refreshAll, enabled = !state.busy) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("刷新可信作者")
                }
            }

            if (state.busy) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

            SectionTitle("候选内容")
            if (state.candidates.isEmpty()) Text("还没有候选内容。可以搜索或粘贴链接。")
            state.candidates.take(20).forEach { item -> CandidateCard(item, viewModel::trust) }

            if (state.candidateCreators.isNotEmpty()) {
                SectionTitle("候选作者")
                state.candidateCreators.forEach { creator ->
                    CreatorCard(
                        creator = creator,
                        primaryLabel = "信任",
                        onPrimary = { viewModel.trust(creator) },
                        onBlock = { viewModel.block(creator) },
                    )
                }
            }

            HorizontalDivider()
            SectionTitle("可信作者（${state.trustedCreators.size}/3 起）")
            if (state.trustedCreators.isEmpty()) Text("尚未添加可信作者。")
            state.trustedCreators.forEach { creator ->
                CreatorCard(
                    creator = creator,
                    primaryLabel = "移出可信",
                    onPrimary = { viewModel.removeTrust(creator) },
                    onBlock = { viewModel.block(creator) },
                )
            }

            if (state.blockedCreators.isNotEmpty()) {
                SectionTitle("已屏蔽作者")
                state.blockedCreators.forEach { creator ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(creator.name, style = MaterialTheme.typography.titleMedium)
                            Text("UID ${creator.mid}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { viewModel.unblock(creator) }) { Text("取消屏蔽") }
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("内容过滤")
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
            SectionTitle("最近观看")
            if (state.history.isEmpty()) Text("还没有观看记录。")
            state.history.take(10).forEach { item ->
                Text(
                    "${item.drama.title} · ${(item.watch.completion * 100).toInt()}% · ${DateFormat.getDateTimeInstance().format(Date(item.watch.lastWatchedAt))}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()
            SectionTitle("应用更新")
            UpdatePanel(state.updateState, viewModel)

            Spacer(Modifier.height(8.dp))
            if (onboarding) {
                Button(
                    onClick = { viewModel.finishSetup(onExit) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text("完成设置，进入奶奶模式")
                }
            } else {
                OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回看剧") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CandidateCard(item: DramaEntity, onTrust: (DramaEntity) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.ownerName} · ${item.bvid}")
            Button(onClick = { onTrust(item) }) { Text("信任这个作者") }
        }
    }
}

@Composable
private fun CreatorCard(
    creator: CreatorEntity,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBlock: () -> Unit,
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
            IconButton(onClick = onBlock) { Icon(Icons.Default.Block, contentDescription = "屏蔽") }
        }
    }
}

@Composable
private fun UpdatePanel(state: UpdateState, viewModel: CaregiverViewModel) {
    when (state) {
        UpdateState.Idle -> Button(onClick = viewModel::checkUpdate) { Text("检查更新") }
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
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

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
}
