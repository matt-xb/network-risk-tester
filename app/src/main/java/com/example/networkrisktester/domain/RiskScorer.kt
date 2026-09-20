package com.example.networkrisktester.domain

import com.example.networkrisktester.data.CloudflareTrace
import com.example.networkrisktester.data.DetectionReport
import com.example.networkrisktester.data.DnsSignal
import com.example.networkrisktester.data.GeoAsnAssessment
import com.example.networkrisktester.data.IpDiscoveryResult
import com.example.networkrisktester.data.IpSignal
import com.example.networkrisktester.data.IpTypeAssessment
import com.example.networkrisktester.data.LeakAssessment
import com.example.networkrisktester.data.NetworkQuality
import com.example.networkrisktester.data.RiskFlag
import com.example.networkrisktester.data.ScoreResult

object RiskScorer {
    fun buildReport(
        primary: IpSignal?,
        geoSignals: List<IpSignal>,
        ipDiscovery: IpDiscoveryResult?,
        cloudflareTrace: CloudflareTrace?,
        dns: DnsSignal?,
        ipType: IpTypeAssessment,
        geoAsn: GeoAsnAssessment,
        leak: LeakAssessment,
        networkQuality: NetworkQuality,
        timestamp: Long = System.currentTimeMillis(),
        isIncomplete: Boolean = false,
        statusMessage: String = ""
    ): DetectionReport {
        val flags = mutableListOf<RiskFlag>()
        val evidence = mutableListOf<String>()

        val ipTypeScore = scoreIpType(ipType, flags, evidence)
        val dnsScore = scoreDns(dns, flags, evidence)
        val geoScore = scoreGeoAsn(geoAsn, geoSignals, flags, evidence)
        val speedScore = scoreNetwork(networkQuality, flags, evidence)
        val leakScore = scoreLeaks(leak, flags, evidence)
        val finalScore = (ipTypeScore + dnsScore + geoScore + speedScore + leakScore)
            .let { if (isIncomplete) (it - 8).coerceAtLeast(0) else it }
            .coerceIn(0, 100)

        val grade = when (finalScore) {
            in 90..100 -> "优秀"
            in 75..89 -> "可用"
            in 60..74 -> "一般"
            in 40..59 -> "风险较高"
            else -> "不建议用于高风控平台"
        }
        val summary = when {
            isIncomplete -> "检测未完整完成，结论仅供参考"
            flags.any { it.section == "DNS 安全" && it.points >= 12 } -> "DNS 存在明显泄露风险"
            flags.any { it.section == "IPv6 / WebRTC" && it.points >= 8 } -> "存在 IPv6 或 WebRTC 泄露风险"
            ipType.label.contains("代理") || ipType.label.contains("云服务器") || ipType.label.contains("机房") -> "出口 IP 类型可能不适合高风控平台"
            else -> "基础体检未发现明显高风险信号"
        }

        val advice = mutableListOf<String>()
        advice += "本 App 为不依赖 API Key 的网络 IP 体检工具，结论来自公开无 Key 接口和本机网络状态。"
        advice += "WebRTC 和 DNS 泄露建议使用内置人工复核入口再次确认。"
        if (dns?.leakSuspected == true) advice += "建议更换 DNS 或检查代理客户端的 DNS 接管设置。"
        if (leak.ipv6LeakSuspected) advice += "建议关闭系统 IPv6 或确认代理客户端已接管 IPv6 流量。"
        if (networkQuality.packetLoss?.failureRate != null && networkQuality.packetLoss.failureRate > 5.0) advice += "当前连接失败率偏高，建议更换网络后重测。"

        return DetectionReport(
            timestamp = timestamp,
            primary = primary,
            geoSignals = geoSignals,
            ipDiscovery = ipDiscovery,
            cloudflareTrace = cloudflareTrace,
            dns = dns,
            ipType = ipType,
            geoAsn = geoAsn,
            leak = leak,
            networkQuality = networkQuality,
            score = ScoreResult(finalScore, grade, summary, "地理/ASN 匹配 ${geoScore}/20", "人工复核入口已提供", "自动检测完成", flags.sortedByDescending { it.points }, evidence),
            advice = advice,
            isIncomplete = isIncomplete,
            statusMessage = statusMessage
        )
    }

    private fun scoreIpType(ipType: IpTypeAssessment, flags: MutableList<RiskFlag>, evidence: MutableList<String>): Int {
        var score = 30
        evidence += "IP 类型可信度：${ipType.label}，置信度 ${ipType.confidence}%，满分 30。"
        ipType.evidence.forEach { evidence += "IP 类型依据：$it" }
        when {
            ipType.label.contains("代理") -> score -= deduct(flags, "疑似代理 / VPN IP", "公开接口字段或 ISP/组织关键词指向代理、VPN、隐私中继。", 22, "IP 类型可信度")
            ipType.label.contains("云服务器") -> score -= deduct(flags, "疑似云服务器 IP", "公开接口标记 hosting 或组织包含云服务商/服务器关键词。", 18, "IP 类型可信度")
            ipType.label.contains("机房") -> score -= deduct(flags, "疑似机房 IP", "ASN/组织名包含机房或托管设施关键词。", 16, "IP 类型可信度")
            ipType.label.contains("移动") -> score -= deduct(flags, "疑似移动网络", "移动网络可用，但在部分平台可能波动较大。", 4, "IP 类型可信度")
            ipType.label == "无法判断" -> score -= deduct(flags, "IP 类型无法判断", "公开接口返回信息不足。", 12, "IP 类型可信度")
        }
        if (ipType.confidence < 60) score -= deduct(flags, "IP 类型置信度偏低", "可用来源较少或字段不完整。", 5, "IP 类型可信度")
        return score.coerceIn(0, 30)
    }

    private fun scoreDns(dns: DnsSignal?, flags: MutableList<RiskFlag>, evidence: MutableList<String>): Int {
        var score = 25
        evidence += "DNS 安全：${dns?.evidence?.joinToString("；") ?: "未获取到 DNS 信息"}，满分 25。"
        if (dns == null || dns.error != null) score -= deduct(flags, "DNS 信息获取失败", dns?.error ?: "无法读取系统 DNS。", 8, "DNS 安全")
        if (dns?.leakSuspected == true) score -= deduct(flags, "DNS 疑似泄露", "DNS 国家与出口 IP 国家不一致，或美国出口搭配中国大陆 DNS。", 15, "DNS 安全")
        if (dns?.abnormalAsn == true) score -= deduct(flags, "DNS ASN 异常", "DNS 服务商与出口 IP 国家/网络明显不一致。", 6, "DNS 安全")
        return score.coerceIn(0, 25)
    }

    private fun scoreGeoAsn(geoAsn: GeoAsnAssessment, signals: List<IpSignal>, flags: MutableList<RiskFlag>, evidence: MutableList<String>): Int {
        var score = 20
        evidence += "地理位置与 ASN 匹配：满分 20。"
        geoAsn.evidence.forEach { evidence += "地理/ASN 依据：$it" }
        if (signals.count { it.status.name == "SUCCESS" } < 2) score -= deduct(flags, "可用 IP 库不足", "少于两个公开 IP 库返回有效结果。", 5, "地理位置与 ASN 匹配")
        if (!geoAsn.countriesConsistent) score -= deduct(flags, "多个 IP 库国家不一致", "国家结果存在冲突。", 7, "地理位置与 ASN 匹配")
        if (!geoAsn.citiesConsistent) score -= deduct(flags, "多个 IP 库城市不一致", "城市结果存在冲突。", 3, "地理位置与 ASN 匹配")
        if (!geoAsn.asnMatchesIsp) score -= deduct(flags, "ASN 与 ISP 信息不足", "ASN、ISP 或组织信息不完整，难以交叉确认。", 4, "地理位置与 ASN 匹配")
        if (geoAsn.ipDnsCountryConsistent == false) score -= deduct(flags, "IP 国家与 DNS 国家不一致", "出口 IP 与 DNS 国家不一致。", 5, "地理位置与 ASN 匹配")
        return score.coerceIn(0, 20)
    }

    private fun scoreNetwork(quality: NetworkQuality, flags: MutableList<RiskFlag>, evidence: MutableList<String>): Int {
        var score = 15
        val avg = quality.avgLatencyMs
        val failureRate = quality.packetLoss?.failureRate
        val down = quality.speed?.downloadMbps
        val up = quality.speed?.uploadMbps
        evidence += "速度与稳定性：平均延迟=${avg?.let { "%.0f ms".format(it) } ?: "未获取到"}，失败率=${failureRate?.let { "%.1f%%".format(it) } ?: "未获取到"}，下载=${down?.let { "%.2f Mbps".format(it) } ?: "未获取到"}，上传=${up?.let { "%.2f Mbps".format(it) } ?: "未获取到"}，满分 15。"
        when {
            avg == null -> score -= deduct(flags, "延迟测试失败", "没有取得有效延迟样本。", 4, "速度与稳定性")
            avg >= 400 -> score -= deduct(flags, "平均延迟过高", "平均延迟 ${"%.0f".format(avg)} ms。", 6, "速度与稳定性")
            avg >= 200 -> score -= deduct(flags, "平均延迟偏高", "平均延迟 ${"%.0f".format(avg)} ms。", 4, "速度与稳定性")
        }
        if (failureRate != null && failureRate > 10) score -= deduct(flags, "连接失败率高", "连接失败率 ${"%.1f".format(failureRate)}%。", 5, "速度与稳定性")
        else if (failureRate != null && failureRate > 3) score -= deduct(flags, "连接失败率偏高", "连接失败率 ${"%.1f".format(failureRate)}%。", 3, "速度与稳定性")
        if (down == null) score -= deduct(flags, "下载测速失败", quality.speed?.error ?: "下载速度未获取到。", 2, "速度与稳定性")
        else if (down < 2.0) score -= deduct(flags, "下载速度较低", "下载速度 %.2f Mbps。".format(down), 2, "速度与稳定性")
        if (up == null) score -= deduct(flags, "上传测速失败", "上传速度未获取到。", 2, "速度与稳定性")
        if ((quality.stability?.maxConsecutiveFailures ?: 0) >= 2) score -= deduct(flags, "稳定性连续失败", "稳定性测试出现连续失败。", 3, "速度与稳定性")
        return score.coerceIn(0, 15)
    }

    private fun scoreLeaks(leak: LeakAssessment, flags: MutableList<RiskFlag>, evidence: MutableList<String>): Int {
        var score = 10
        evidence += "IPv6 / WebRTC 泄露风险：${leak.evidence.joinToString("；")}，满分 10。"
        if (leak.ipv6LeakSuspected) score -= deduct(flags, "IPv6 疑似泄露", "IPv4 可能走代理但 IPv6 同时暴露，需要人工确认。", 8, "IPv6 / WebRTC")
        score -= deduct(flags, "WebRTC 需要人工复核", "Android App 无法代替浏览器 WebRTC 页面检测，已提供入口。", 1, "IPv6 / WebRTC")
        return score.coerceIn(0, 10)
    }

    private fun deduct(flags: MutableList<RiskFlag>, title: String, detail: String, points: Int, section: String): Int {
        flags += RiskFlag(title, detail, points, section)
        return points
    }
}
