package com.example.networkrisktester.domain

import com.example.networkrisktester.data.IpSignal

object NetworkClassifier {
    private val datacenterKeywords = listOf(
        "hosting", "cloud", "data center", "datacenter", "server", "vps",
        "amazon", "google cloud", "azure", "oracle", "digitalocean", "linode",
        "hetzner", "ovh", "cloudflare", "m247", "datacamp"
    )
    private val mobileKeywords = listOf("mobile", "cellular", "wireless", "5g", "4g", "lte")
    private val residentialKeywords = listOf("broadband", "cable", "fiber", "fibre", "telecom", "comcast", "verizon", "att")

    fun hasDatacenterKeyword(signal: IpSignal?): Boolean {
        val text = "${signal?.isp.orEmpty()} ${signal?.organization.orEmpty()}".lowercase()
        return datacenterKeywords.any { text.contains(it) }
    }

    fun label(signal: IpSignal?): String {
        if (signal == null) return "未获取到"
        val text = "${signal.isp.orEmpty()} ${signal.organization.orEmpty()}".lowercase()
        return when {
            signal.isMobile == true || mobileKeywords.any { text.contains(it) } -> "手机移动网络"
            signal.isHosting == true || hasDatacenterKeyword(signal) -> "数据中心 / Hosting"
            residentialKeywords.any { text.contains(it) } -> "住宅宽带 / 商业宽带"
            signal.isProxy == true -> "代理网络"
            else -> "商业宽带或未知类型"
        }
    }
}
