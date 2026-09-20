package com.example.networkrisktester.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.networkrisktester.data.DetectionProgress
import com.example.networkrisktester.data.DetectionReport
import com.example.networkrisktester.data.HistoryEntity
import com.example.networkrisktester.data.SourceConfig
import com.example.networkrisktester.data.TimeFormat
import com.example.networkrisktester.data.formatMs
import com.example.networkrisktester.data.formatPercent

private val AppColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    background = Color(0xFFF7F8FA),
    surface = Color.White
)

@Composable
fun DetectionApp(viewModel: DetectionViewModel) {
    MaterialTheme(colorScheme = AppColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            val state by viewModel.uiState.collectAsState()
            val history by viewModel.history.collectAsState()
            NavHost(navController = nav, startDestination = "dashboard") {
                composable("dashboard") { DashboardScreen(nav, viewModel, state, history) }
                composable("detail") { DetailScreen(nav, viewModel, state) }
                composable("review") { ReviewScreen(nav) }
                composable("history") { HistoryScreen(nav, history, viewModel) }
                composable("settings") { SettingsScreen(nav, viewModel, state.settings) }
                composable(
                    "web?title={title}&url={url}",
                    arguments = listOf(navArgument("title") { type = NavType.StringType }, navArgument("url") { type = NavType.StringType })
                ) { entry ->
                    WebViewScreen(nav, entry.arguments?.getString("title").orEmpty(), entry.arguments?.getString("url").orEmpty())
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(nav: NavController, viewModel: DetectionViewModel, state: DetectionUiState, history: List<HistoryEntity>) {
    PageScaffold("网络 IP 体检", actions = { TextButton(onClick = { nav.navigate("settings") }) { Text("设置") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NoticeCard("不依赖 API Key，不需要填写 IP。点击一次即可检测当前设备网络出口。") }
            item { SummaryCard(state.report, state.isLoading, state.progress) }
            if (state.isLoading || state.progress.completed > 0) item { ProgressCard(state.progress) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.startDetection() }, enabled = !state.isLoading, modifier = Modifier.weight(1f)) {
                        Text(if (state.isLoading) "检测中..." else if (state.report == null) "一键检测" else "重新检测")
                    }
                    OutlinedButton(onClick = { viewModel.cancelDetection() }, enabled = state.isLoading, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { nav.navigate("detail") }, enabled = state.report != null, modifier = Modifier.weight(1f)) { Text("结果详情") }
                    OutlinedButton(onClick = { nav.navigate("review") }, modifier = Modifier.weight(1f)) { Text("人工复核") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { nav.navigate("history") }, modifier = Modifier.weight(1f)) { Text("历史记录") }
                    OutlinedButton(onClick = { nav.navigate("settings") }, modifier = Modifier.weight(1f)) { Text("检测设置") }
                }
            }
            state.report?.let {
                item { IpIdentityCard(it) }
                item { DnsLeakCard(it) }
                item { QualityCard(it) }
                item { EvidenceCard(it) }
            }
            state.error?.let { item { NoticeCard("检测失败：$it") } }
            state.savedMessage?.let { item { NoticeCard(it) } }
            state.exportPath?.let { item { NoticeCard("已导出：$it") } }
            if (history.isNotEmpty()) {
                item { SectionTitle("最近记录") }
                items(history.take(3)) { entity -> HistoryRow(entity) { viewModel.openHistory(entity); nav.navigate("detail") } }
            }
        }
    }
}

@Composable
private fun DetailScreen(nav: NavController, viewModel: DetectionViewModel, state: DetectionUiState) {
    val context = LocalContext.current
    val report = state.report
    PageScaffold("结果详情", onBack = { nav.popBackStack() }) { padding ->
        if (report == null) {
            LazyColumn(contentPadding = padding) { item { NoticeCard(state.historyText ?: "还没有检测报告。") } }
            return@PageScaffold
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { copyReport(context, report.toText()) }, modifier = Modifier.weight(1f)) { Text("复制完整报告") }
                    OutlinedButton(onClick = { viewModel.exportCurrentReport() }, modifier = Modifier.weight(1f)) { Text("导出 TXT") }
                }
            }
            item { SummaryCard(report, false, DetectionProgress()) }
            item { IpIdentityCard(report) }
            item { IpSourceCard(report) }
            item { DnsLeakCard(report) }
            item { LeakCard(report) }
            item { QualityCard(report) }
            item { GeoAsnCard(report) }
            item { EvidenceCard(report) }
            item { RiskFlagCard(report) }
            item { InfoCard("建议", report.advice) }
            item { Button(onClick = { viewModel.saveCurrentReportToHistory() }, modifier = Modifier.fillMaxWidth()) { Text("保存到历史记录") } }
        }
    }
}

@Composable
private fun ReviewScreen(nav: NavController) {
    PageScaffold("人工复核入口", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NoticeCard("以下页面会自动读取当前浏览器网络环境。DNS 和 WebRTC 建议至少各打开一个复核。") }
            reviewSites.forEach { site ->
                item { CardButton(site.title, site.url) { nav.navigate("web?title=${Uri.encode(site.title)}&url=${Uri.encode(site.url)}") } }
            }
        }
    }
}

@Composable
private fun SettingsScreen(nav: NavController, viewModel: DetectionViewModel, config: SourceConfig) {
    var local by remember(config) { mutableStateOf(config) }
    PageScaffold("检测设置", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ToggleCard("检测 IPv6", local.ipv6Enabled, "用于判断 IPv6 是否可能绕过代理暴露真实网络。") { local = local.copy(ipv6Enabled = it) } }
            item { ToggleCard("下载测速", local.downloadTestEnabled, "使用无 API Key 的公开测速端点进行快速下载测试。") { local = local.copy(downloadTestEnabled = it) } }
            item { ToggleCard("稳定性测试", local.stabilityTestEnabled, "会多次请求公开端点，耗时稍长。") { local = local.copy(stabilityTestEnabled = it) } }
            item { NoticeCard("本 App 不提供 API Key 设置，不接入高级信誉库或黑名单 API。") }
            item { Button(onClick = { viewModel.saveSettings(local); nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("保存") } }
        }
    }
}

@Composable
private fun HistoryScreen(nav: NavController, history: List<HistoryEntity>, viewModel: DetectionViewModel) {
    PageScaffold("历史记录", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (history.isEmpty()) item { NoticeCard("暂无历史记录。") }
            items(history) { entity -> HistoryRow(entity) { viewModel.openHistory(entity); nav.navigate("detail") } }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewScreen(nav: NavController, title: String, url: String) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else nav.popBackStack() }
    Scaffold(topBar = {
        TopBar(title, onBack = { if (webView?.canGoBack() == true) webView?.goBack() else nav.popBackStack() }, actions = {
            TextButton(onClick = { webView?.reload() }) { Text("刷新") }
            TextButton(onClick = { nav.popBackStack() }) { Text("关闭") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                            if (request?.isForMainFrame == true) { loading = false; error = err?.description?.toString() ?: "页面加载失败" }
                        }
                    }
                    webView = this
                    loadUrl(url)
                }
            }, update = { webView = it })
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            error?.let { NoticeCard("加载失败：$it", Modifier.align(Alignment.TopCenter).padding(12.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(report: DetectionReport?, loading: Boolean, progress: DetectionProgress) = CardBlock("总览") {
    Text(report?.let { "${it.score.score} 分 · ${it.score.grade}" } ?: if (loading) "正在检测..." else "点击一键检测", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(if (loading) progress.currentStep.ifBlank { "准备中" } else report?.score?.summary ?: "默认检测当前设备网络出口 IP。")
    KeyValue("当前 IPv4", report?.ipDiscovery?.ipv4 ?: progress.confirmedIp ?: "未检测")
    KeyValue("当前 IPv6", report?.ipDiscovery?.ipv6 ?: "未检测")
    KeyValue("IP 类型", report?.ipType?.label ?: "未判断")
    KeyValue("DNS 泄露", report?.dns?.let { if (it.leakSuspected) "疑似泄露" else "未发现明确泄露" } ?: "未检测")
    KeyValue("IPv6 风险", report?.let { if (it.leak.ipv6LeakSuspected) "高风险" else "未发现明确风险" } ?: "未检测")
}

@Composable
private fun ProgressCard(progress: DetectionProgress) = CardBlock("检测进度") {
    LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
    KeyValue("当前步骤", progress.currentStep.ifBlank { "等待中" })
    KeyValue("进度", "${progress.percent}%")
    progress.steps.forEach { KeyValue(it.name, it.state.label) }
}

@Composable
private fun IpIdentityCard(report: DetectionReport) = CardBlock("IP 身份") {
    KeyValue("IPv4", report.ipDiscovery?.ipv4 ?: "未获取到")
    KeyValue("IPv6", report.ipDiscovery?.ipv6 ?: "未获取到")
    KeyValue("IP 类型", "${report.ipType.label}（置信度 ${report.ipType.confidence}%）")
    KeyValue("ASN / ISP", "${report.primary?.asn ?: "未知"} / ${report.primary?.isp ?: "未知"}")
    KeyValue("组织名称", report.primary?.organization ?: "未知")
    KeyValue("地理位置", "${report.primary?.country ?: "未知"} ${report.primary?.city ?: ""}".trim())
    KeyValue("时区", report.primary?.timezone ?: "未知")
}

@Composable
private fun DnsLeakCard(report: DetectionReport) = CardBlock("DNS 安全") {
    KeyValue("DNS 服务器", report.dns?.servers?.joinToString() ?: "未获取到")
    KeyValue("DNS 国家", report.dns?.countryCode ?: report.dns?.country ?: "未知")
    KeyValue("DNS ASN / ISP", "${report.dns?.asn ?: "未知"} / ${report.dns?.isp ?: "未知"}")
    KeyValue("是否疑似泄露", if (report.dns?.leakSuspected == true) "是" else "否或无法确认")
    report.dns?.evidence.orEmpty().forEach { Text(it, color = Color(0xFF374151)) }
}

@Composable
private fun LeakCard(report: DetectionReport) = CardBlock("IPv6 / WebRTC 泄露") {
    KeyValue("IPv6 泄露风险", if (report.leak.ipv6LeakSuspected) "高风险" else "未发现明确风险")
    KeyValue("WebRTC", report.leak.webRtcRisk)
    report.leak.evidence.forEach { Text(it, color = Color(0xFF374151)) }
}

@Composable
private fun QualityCard(report: DetectionReport) = CardBlock("网速与稳定性") {
    val q = report.networkQuality
    KeyValue("平均延迟", q.avgLatencyMs?.formatMs() ?: "未获取到")
    KeyValue("最小 / 最大延迟", "${q.minLatencyMs?.let { "$it ms" } ?: "未知"} / ${q.maxLatencyMs?.let { "$it ms" } ?: "未知"}")
    KeyValue("抖动", q.jitterMs?.formatMs() ?: "未获取到")
    KeyValue("连接失败率", q.packetLoss?.failureRate?.formatPercent() ?: "未获取到")
    KeyValue("下载速度", q.speed?.downloadMbps?.let { "%.2f Mbps".format(it) } ?: "未获取到")
    KeyValue("上传速度", q.speed?.uploadMbps?.let { "%.2f Mbps".format(it) } ?: "未获取到")
    KeyValue("稳定性评分", q.stability?.level ?: "未获取到")
}

@Composable
private fun GeoAsnCard(report: DetectionReport) = CardBlock("地理位置与 ASN 匹配") {
    KeyValue("国家是否一致", if (report.geoAsn.countriesConsistent) "一致" else "不一致或不足")
    KeyValue("城市是否一致", if (report.geoAsn.citiesConsistent) "一致" else "不一致或不足")
    KeyValue("ASN 是否匹配 ISP", if (report.geoAsn.asnMatchesIsp) "匹配" else "不足以确认")
    KeyValue("IP 国家与 DNS 国家", report.geoAsn.ipDnsCountryConsistent?.let { if (it) "一致" else "不一致" } ?: "无法确认")
    report.geoAsn.evidence.forEach { Text(it, color = Color(0xFF374151)) }
}

@Composable
private fun IpSourceCard(report: DetectionReport) = CardBlock("公开接口交叉验证") {
    report.ipDiscovery?.sources?.forEach { KeyValue(it.source, it.ip ?: it.error ?: "失败") }
    report.geoSignals.forEach { KeyValue(it.source, "${it.country ?: "未知"} / ${it.city ?: "未知"} / ${it.asn ?: "未知"}") }
    report.ipDiscovery?.warning?.let { Text(it, color = Color(0xFF92400E)) }
}

@Composable
private fun EvidenceCard(report: DetectionReport) = InfoCard("每项判断依据", report.score.evidence)

@Composable
private fun RiskFlagCard(report: DetectionReport) = InfoCard(
    "风险项",
    if (report.score.flags.isEmpty()) listOf("暂无明显风险项") else report.score.flags.map { "[${it.section}] ${it.title}：${it.detail}（-${it.points}）" }
)

@Composable
private fun ToggleCard(title: String, enabled: Boolean, description: String, onChange: (Boolean) -> Unit) = CardBlock(title) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(if (enabled) "已开启" else "已关闭")
        Switch(checked = enabled, onCheckedChange = onChange)
    }
    Text(description, color = Color(0xFF6B7280))
}

@Composable
private fun InfoCard(title: String, rows: List<String>) = CardBlock(title) { rows.forEach { Text(it, color = Color(0xFF374151)) } }

@Composable
private fun NoticeCard(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)), modifier = modifier) {
        Text(text, modifier = Modifier.padding(14.dp), color = Color(0xFF92400E))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color(0xFF6B7280), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HistoryRow(entity: HistoryEntity, onClick: () -> Unit) {
    CardButton("${entity.grade} / ${entity.score} 分  ${entity.ip ?: "未知 IP"}", "${TimeFormat.format(entity.timestamp)}  ${entity.country ?: ""} ${entity.city ?: ""}", onClick)
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = Color(0xFF6B7280), modifier = Modifier.weight(0.9f))
        Text(value, modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CardBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PageScaffold(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopBar(title, onBack, actions) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        content(PaddingValues(14.dp, 12.dp + padding.calculateTopPadding(), 14.dp, 18.dp + padding.calculateBottomPadding()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("返回") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

private fun copyReport(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("network-ip-check-report", text))
}

private val reviewSites = listOf(
    CheckSite("DNS 人工复核", "ipleak.net", "https://ipleak.net/"),
    CheckSite("DNS 人工复核", "BrowserLeaks DNS", "https://browserleaks.com/dns"),
    CheckSite("DNS 人工复核", "DNSLeakTest", "https://www.dnsleaktest.com/"),
    CheckSite("WebRTC 人工复核", "BrowserLeaks WebRTC", "https://browserleaks.com/webrtc"),
    CheckSite("WebRTC 人工复核", "ipleak.net", "https://ipleak.net/")
)
