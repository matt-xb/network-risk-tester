package com.example.networkrisktester.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networkrisktester.data.AppDatabase
import com.example.networkrisktester.data.DEFAULT_STEPS
import com.example.networkrisktester.data.DetectionProgress
import com.example.networkrisktester.data.DetectionReport
import com.example.networkrisktester.data.DetectionService
import com.example.networkrisktester.data.DetectionStepProgress
import com.example.networkrisktester.data.HistoryEntity
import com.example.networkrisktester.data.HistoryRepository
import com.example.networkrisktester.data.SettingsRepository
import com.example.networkrisktester.data.SourceConfig
import com.example.networkrisktester.data.StepState
import com.example.networkrisktester.domain.RiskScorer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DetectionUiState(
    val isLoading: Boolean = false,
    val progress: DetectionProgress = DetectionProgress(),
    val report: DetectionReport? = null,
    val historyText: String? = null,
    val error: String? = null,
    val exportPath: String? = null,
    val savedMessage: String? = null,
    val settings: SourceConfig = SourceConfig()
)

class DetectionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HistoryRepository(AppDatabase.get(application).historyDao())
    private val settingsRepository = SettingsRepository(application)
    private val service = DetectionService(application)
    private var detectionJob: Job? = null
    @Volatile private var cancelRequested = false

    private val _uiState = MutableStateFlow(DetectionUiState(settings = settingsRepository.load()))
    val uiState: StateFlow<DetectionUiState> = _uiState

    val history: StateFlow<List<HistoryEntity>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startDetection() {
        if (_uiState.value.isLoading) return
        cancelRequested = false
        detectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, progress = DetectionProgress(), error = null, exportPath = null, savedMessage = null, historyText = null) }
            runCatching {
                val bundle = service.detectAll(
                    config = _uiState.value.settings,
                    onProgress = { progress -> _uiState.update { it.copy(progress = progress) } },
                    shouldCancel = { cancelRequested }
                )
                RiskScorer.buildReport(
                    primary = bundle.primary,
                    geoSignals = bundle.geoSignals,
                    ipDiscovery = bundle.ipDiscovery,
                    cloudflareTrace = bundle.cloudflareTrace,
                    dns = bundle.dns,
                    ipType = bundle.ipType,
                    geoAsn = bundle.geoAsn,
                    leak = bundle.leak,
                    networkQuality = bundle.networkQuality,
                    isIncomplete = bundle.isIncomplete,
                    statusMessage = bundle.statusMessage
                )
            }.onSuccess { report ->
                val progress = if (report.isIncomplete) {
                    _uiState.value.progress.copy(cancelled = true)
                } else {
                    DetectionProgress("检测完成", DEFAULT_STEPS.size, DEFAULT_STEPS.size, DEFAULT_STEPS.map { DetectionStepProgress(it, StepState.SUCCESS) }, report.ipDiscovery?.confirmedIp)
                }
                _uiState.update { it.copy(isLoading = false, progress = progress, report = report, error = report.statusMessage.takeIf { msg -> msg.startsWith("无法获取") }) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isLoading = false, error = throwable.message ?: "检测失败，请稍后重试") }
            }
        }
    }

    fun cancelDetection() {
        cancelRequested = true
        _uiState.update { it.copy(savedMessage = "正在取消，已完成的数据会保留。") }
    }

    fun saveSettings(config: SourceConfig) {
        settingsRepository.save(config)
        _uiState.update { it.copy(settings = config, savedMessage = "设置已保存") }
    }

    fun saveCurrentReportToHistory() {
        val report = _uiState.value.report ?: return
        viewModelScope.launch {
            repository.save(report)
            _uiState.update { it.copy(savedMessage = "已保存到历史记录") }
        }
    }

    fun openHistory(entity: HistoryEntity) {
        _uiState.update { it.copy(report = null, historyText = entity.reportText, error = null, exportPath = null, savedMessage = null) }
    }

    fun exportCurrentReport() {
        val text = _uiState.value.report?.toText() ?: _uiState.value.historyText ?: return
        viewModelScope.launch {
            runCatching {
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "reports")
                dir.mkdirs()
                val file = File(dir, "network_report_${System.currentTimeMillis()}.txt")
                file.writeText(text)
                file.absolutePath
            }.onSuccess { path ->
                _uiState.update { it.copy(exportPath = path) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "导出失败") }
            }
        }
    }
}
