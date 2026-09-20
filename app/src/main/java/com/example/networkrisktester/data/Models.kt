package com.example.networkrisktester.data

import androidx.room.Entity
import androidx.room.PrimaryKey
data class SourceConfig(
    val ipv6Enabled: Boolean = true,
    val downloadTestEnabled: Boolean = true,
    val stabilityTestEnabled: Boolean = true
)

enum class SourceStatus { SUCCESS, FAILED, SKIPPED }

enum class StepState(val label: String) {
    WAITING("等待中"),
    RUNNING("检测中"),
    SUCCESS("完成"),
    FAILED("失败，已跳过"),
    SKIPPED("已跳过"),
    CANCELLED("已取消")
}

data class DetectionStepProgress(
    val name: String,
    val state: StepState = StepState.WAITING,
    val message: String = ""
)

val DEFAULT_STEPS = listOf(
    "获取 IPv4 / IPv6",
    "多源交叉验证 IP",
    "检测 DNS 信息",
    "判断 IP 类型",
    "测试延迟",
    "测试请求失败率",
    "测试网络稳定性",
    "测试下载速度",
    "测试上传速度",
    "生成评分报告",
    "检测完成"
)

data class DetectionProgress(
    val currentStep: String = "",
    val completed: Int = 0,
    val total: Int = DEFAULT_STEPS.size,
    val steps: List<DetectionStepProgress> = DEFAULT_STEPS.map { DetectionStepProgress(it) },
    val confirmedIp: String? = null,
    val cancelled: Boolean = false
) {
    val percent: Int get() = if (total == 0) 0 else (completed * 100 / total).coerceIn(0, 100)
}

data class IpSourceResult(
    val source: String,
    val ip: String? = null,
    val status: SourceStatus = SourceStatus.SUCCESS,
    val error: String? = null
)

data class IpDiscoveryResult(
    val confirmedIp: String?,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val sources: List<IpSourceResult>,
    val consistencyMessage: String,
    val warning: String? = null
)

data class IpSignal(
    val source: String,
    val ip: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val region: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val asn: String? = null,
    val organization: String? = null,
    val timezone: String? = null,
    val isMobile: Boolean? = null,
    val isProxy: Boolean? = null,
    val isHosting: Boolean? = null,
    val status: SourceStatus = SourceStatus.SUCCESS,
    val error: String? = null
)

data class CloudflareTrace(
    val ip: String? = null,
    val colo: String? = null,
    val loc: String? = null,
    val tls: String? = null,
    val http: String? = null,
    val raw: Map<String, String> = emptyMap(),
    val error: String? = null
)

data class DnsSignal(
    val servers: List<String> = emptyList(),
    val country: String? = null,
    val countryCode: String? = null,
    val asn: String? = null,
    val isp: String? = null,
    val organization: String? = null,
    val leakSuspected: Boolean = false,
    val abnormalAsn: Boolean = false,
    val evidence: List<String> = emptyList(),
    val error: String? = null
)

data class IpTypeAssessment(
    val label: String,
    val confidence: Int,
    val evidence: List<String>
)

data class GeoAsnAssessment(
    val countriesConsistent: Boolean,
    val citiesConsistent: Boolean,
    val asnMatchesIsp: Boolean,
    val ipDnsCountryConsistent: Boolean?,
    val evidence: List<String>
)

data class LeakAssessment(
    val ipv6LeakSuspected: Boolean,
    val webRtcRisk: String,
    val evidence: List<String>
)

data class LatencyTargetResult(val target: String, val samplesMs: List<Long>, val failures: Int) {
    val minMs: Long? get() = samplesMs.minOrNull()
    val maxMs: Long? get() = samplesMs.maxOrNull()
    val avgMs: Double? get() = samplesMs.takeIf { it.isNotEmpty() }?.average()
    val jitterMs: Double? get() = calculateJitter(samplesMs)
}

data class PacketLossSignal(val target: String, val total: Int, val failures: Int, val avgMs: Double?) {
    val failureRate: Double get() = if (total == 0) 0.0 else failures * 100.0 / total
}

data class StabilitySignal(
    val target: String,
    val attempts: Int,
    val successes: Int,
    val avgMs: Double?,
    val maxMs: Long?,
    val jitterMs: Double?,
    val maxConsecutiveFailures: Int,
    val level: String
) {
    val successRate: Double get() = if (attempts == 0) 0.0 else successes * 100.0 / attempts
}

data class SpeedSignal(
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val elapsedMs: Long? = null,
    val bytes: Long? = null,
    val source: String = "Cloudflare quick speed",
    val error: String? = null
)

data class NetworkQuality(
    val latencyTargets: List<LatencyTargetResult> = emptyList(),
    val packetLoss: PacketLossSignal? = null,
    val stability: StabilitySignal? = null,
    val speed: SpeedSignal? = null
) {
    val minLatencyMs: Long? get() = latencyTargets.mapNotNull { it.minMs }.minOrNull()
    val maxLatencyMs: Long? get() = latencyTargets.mapNotNull { it.maxMs }.maxOrNull()
    val avgLatencyMs: Double? get() = latencyTargets.mapNotNull { it.avgMs }.takeIf { it.isNotEmpty() }?.average()
    val jitterMs: Double? get() = latencyTargets.mapNotNull { it.jitterMs }.takeIf { it.isNotEmpty() }?.average()
}

data class RiskFlag(val title: String, val detail: String, val points: Int, val section: String)

data class ScoreResult(
    val score: Int,
    val grade: String,
    val summary: String,
    val basicConclusion: String,
    val reviewConclusion: String,
    val completeness: String,
    val flags: List<RiskFlag>,
    val evidence: List<String>
)

data class DetectionReport(
    val timestamp: Long,
    val primary: IpSignal?,
    val geoSignals: List<IpSignal>,
    val ipDiscovery: IpDiscoveryResult?,
    val cloudflareTrace: CloudflareTrace?,
    val dns: DnsSignal?,
    val ipType: IpTypeAssessment,
    val geoAsn: GeoAsnAssessment,
    val leak: LeakAssessment,
    val networkQuality: NetworkQuality,
    val score: ScoreResult,
    val advice: List<String>,
    val isIncomplete: Boolean = false,
    val statusMessage: String = ""
) {
    fun toText(): String = buildString {
        appendLine("无 API Key 网络 IP 体检报告")
        appendLine("生成时间：${TimeFormat.format(timestamp)}")
        if (isIncomplete) appendLine("报告状态：检测已取消，结果不完整")
        appendLine()
        appendLine("当前 IPv4：${ipDiscovery?.ipv4 ?: "未获取到"}")
        appendLine("当前 IPv6：${ipDiscovery?.ipv6 ?: "未获取到"}")
        appendLine("IP 来源一致性：${ipDiscovery?.consistencyMessage ?: "未获取到"}")
        ipDiscovery?.sources?.forEach { appendLine("${it.source}：${it.ip ?: it.error ?: statusLabel(it.status)}") }
        appendLine("国家 / 城市：${primary?.country ?: "未获取到"} / ${primary?.city ?: "未获取到"}")
        appendLine("ASN / ISP / 组织：${primary?.asn ?: "未获取到"} / ${primary?.isp ?: "未获取到"} / ${primary?.organization ?: "未获取到"}")
        appendLine("IP 类型判断：${ipType.label}（置信度 ${ipType.confidence}%）")
        ipType.evidence.forEach { appendLine("- $it") }
        appendLine()
        appendLine("DNS 服务器：${dns?.servers?.joinToString() ?: "未获取到"}")
        appendLine("DNS 国家 / ASN / ISP：${dns?.country ?: "未获取到"} / ${dns?.asn ?: "未获取到"} / ${dns?.isp ?: "未获取到"}")
        appendLine("DNS 疑似泄露：${if (dns?.leakSuspected == true) "是" else "否或无法确认"}")
        dns?.evidence?.forEach { appendLine("- $it") }
        appendLine("IPv6 泄露风险：${if (leak.ipv6LeakSuspected) "高风险" else "未发现明确风险"}")
        leak.evidence.forEach { appendLine("- $it") }
        appendLine()
        appendLine("平均延迟：${networkQuality.avgLatencyMs?.formatMs() ?: "未获取到"}")
        appendLine("最高延迟：${networkQuality.maxLatencyMs?.let { "$it ms" } ?: "未获取到"}")
        appendLine("Jitter：${networkQuality.jitterMs?.formatMs() ?: "未获取到"}")
        appendLine("请求失败率：${networkQuality.packetLoss?.failureRate?.formatPercent() ?: "未获取到"}")
        appendLine("下载速度：${networkQuality.speed?.downloadMbps?.let { "%.2f Mbps".format(it) } ?: "未获取到"}")
        appendLine("上传速度：${networkQuality.speed?.uploadMbps?.let { "%.2f Mbps".format(it) } ?: "未获取到"}")
        appendLine("稳定性：${networkQuality.stability?.level ?: "未获取到"}")
        appendLine()
        appendLine("地理位置与 ASN 匹配：${score.basicConclusion}")
        geoAsn.evidence.forEach { appendLine("- $it") }
        appendLine("总评分：${score.score} / 100")
        appendLine("风险等级：${score.grade}")
        appendLine("摘要：${score.summary}")
        appendLine()
        appendLine("每项判断依据：")
        score.evidence.forEach { appendLine("- $it") }
        appendLine("风险项：")
        if (score.flags.isEmpty()) appendLine("- 暂无明显扣分项")
        score.flags.forEach { appendLine("- [${it.section}] ${it.title}：${it.detail}（-${it.points}）") }
        appendLine()
        appendLine("人工复核入口：")
        appendLine("- DNS：https://ipleak.net")
        appendLine("- DNS：https://browserleaks.com/dns")
        appendLine("- DNS：https://www.dnsleaktest.com")
        appendLine("- WebRTC：https://browserleaks.com/webrtc")
        appendLine("- WebRTC：https://ipleak.net")
        appendLine("建议：")
        advice.forEach { appendLine("- $it") }
    }
}

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val timestamp: Long,
    val ip: String?,
    val country: String?,
    val city: String?,
    val isp: String?,
    val asn: String?,
    val score: Int,
    val grade: String,
    val reportText: String
)

object TimeFormat {
    fun format(timestamp: Long): String {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return formatter.format(java.util.Date(timestamp))
    }
}

fun statusLabel(status: SourceStatus): String = when (status) {
    SourceStatus.SUCCESS -> "完成"
    SourceStatus.FAILED -> "失败，已跳过"
    SourceStatus.SKIPPED -> "已跳过"
}

fun calculateJitter(samples: List<Long>): Double? {
    if (samples.size < 2) return null
    return samples.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
}

fun Double.formatMs(): String = "%.0f ms".format(this)
fun Double.formatPercent(): String = "%.1f%%".format(this)
