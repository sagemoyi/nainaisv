package com.xmoyi.nainaisv.caregiver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmoyi.nainaisv.NaiNaiApplication
import com.xmoyi.nainaisv.data.CreatorEntity
import com.xmoyi.nainaisv.data.DramaEntity
import com.xmoyi.nainaisv.data.HistoryWithDrama
import com.xmoyi.nainaisv.update.UpdateManifest
import com.xmoyi.nainaisv.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CaregiverUiState(
    val trustedCreators: List<CreatorEntity> = emptyList(),
    val candidateCreators: List<CreatorEntity> = emptyList(),
    val blockedCreators: List<CreatorEntity> = emptyList(),
    val candidates: List<DramaEntity> = emptyList(),
    val history: List<HistoryWithDrama> = emptyList(),
    val positiveTerms: String = "",
    val blockedTerms: String = "",
    val hasPin: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val updateState: UpdateState = UpdateState.Idle,
)

class CaregiverViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NaiNaiApplication).container
    private val repository = container.repository
    private val settings = container.settings
    private val updates = container.updateManager

    private val _state = MutableStateFlow(CaregiverUiState())
    val state: StateFlow<CaregiverUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.trustedCreators.collect { update { copy(trustedCreators = it) } } }
        viewModelScope.launch { repository.candidateCreators.collect { update { copy(candidateCreators = it) } } }
        viewModelScope.launch { repository.blockedCreators.collect { update { copy(blockedCreators = it) } } }
        viewModelScope.launch { repository.candidates.collect { update { copy(candidates = it) } } }
        viewModelScope.launch { repository.history.collect { update { copy(history = it) } } }
        viewModelScope.launch { settings.positiveTerms.collect { update { copy(positiveTerms = it) } } }
        viewModelScope.launch { settings.blockedTerms.collect { update { copy(blockedTerms = it) } } }
        viewModelScope.launch { settings.hasPin.collect { update { copy(hasPin = it) } } }
        viewModelScope.launch { updates.state.collect { update { copy(updateState = it) } } }
    }

    fun savePin(pin: String) = launchAction("PIN 已保存") {
        require(pin.length >= 4 && pin.all(Char::isDigit)) { "PIN 至少为四位数字" }
        settings.savePin(pin)
    }

    fun search(keyword: String) = launchStringAction {
        require(keyword.isNotBlank()) { "请输入搜索词" }
        val count = repository.searchCandidates(keyword.trim())
        "找到 $count 条候选内容"
    }

    fun importLink(link: String) = launchStringAction { repository.importLink(link) }

    fun trust(creator: CreatorEntity) = launchAction("已信任 ${creator.name}") {
        repository.trustCreator(creator.mid, creator.name)
    }

    fun trust(item: DramaEntity) = launchAction("已信任 ${item.ownerName}") {
        repository.trustCreator(item.ownerMid, item.ownerName)
    }

    fun block(creator: CreatorEntity) = launchAction("已屏蔽 ${creator.name}") {
        repository.blockCreator(creator.mid)
    }

    fun unblock(creator: CreatorEntity) = launchAction("已取消屏蔽 ${creator.name}") {
        repository.unblockCreator(creator.mid)
    }

    fun removeTrust(creator: CreatorEntity) = launchAction("已移出可信作者") {
        repository.untrustCreator(creator.mid)
    }

    fun saveTerms(positive: String, blocked: String) = launchAction("过滤规则已保存") {
        settings.setTerms(positive, blocked)
    }

    fun refreshAll() = launchAction("可信作者内容已刷新") { repository.refreshTrustedCreators(force = true) }

    fun finishSetup(onReady: () -> Unit) {
        viewModelScope.launch {
            val current = _state.value
            when {
                !current.hasPin -> update { copy(message = "请先设置家属 PIN") }
                current.trustedCreators.size < 3 -> update { copy(message = "请至少添加三个可信 UP 主") }
                else -> {
                    settings.completeSetup()
                    onReady()
                }
            }
        }
    }

    fun checkUpdate() {
        viewModelScope.launch { updates.check(settings.updateUrl.first()) }
    }

    fun downloadUpdate(manifest: UpdateManifest) {
        viewModelScope.launch { updates.download(manifest) }
    }

    fun installUpdate(state: UpdateState.ReadyToInstall) = updates.install(state.file)

    fun clearMessage() = update { copy(message = null) }

    private fun launchAction(successMessage: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            update { copy(busy = true, message = null) }
            runCatching { block() }
                .onSuccess { update { copy(busy = false, message = successMessage) } }
                .onFailure { error -> update { copy(busy = false, message = error.message ?: "操作失败") } }
        }
    }

    private fun launchStringAction(block: suspend () -> String) {
        viewModelScope.launch {
            update { copy(busy = true, message = null) }
            runCatching { block() }
                .onSuccess { message -> update { copy(busy = false, message = message) } }
                .onFailure { error -> update { copy(busy = false, message = error.message ?: "操作失败") } }
        }
    }

    private inline fun update(transform: CaregiverUiState.() -> CaregiverUiState) {
        _state.value = _state.value.transform()
    }
}
