package com.example.networkrisktester.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class DetectionBundle(
    val primary: IpSignal?,
    val geoSignals: List<IpSignal>,
    val ipDiscovery: IpDiscoveryResult?,
    val cloudflareTrace: CloudflareTrace?,
    val dns: DnsSignal?,
    val ipType: IpTypeAssessment,
    val geoAsn: GeoAsnAssessment,
    val leak: LeakAssessment,
    val networkQuality: NetworkQuality,
    val isIncomplete: Boolean = false,
    val statusMessage: String = ""
)

class DetectionService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val progress = ProgressTracker()

    suspend fun detectAll(
        config: SourceConfig,
        onProgress: suspend (DetectionProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): DetectionBundle = coroutineScope {
        var discovery: IpDiscoveryResult? = null
        var trace: CloudflareTrace? = null
        var geoSignals = emptyList<IpSignal>()
        var primary: IpSignal? = null
        var dns: DnsSignal? = null
        var ipType = IpTypeAssessment("无法判断", 0, listOf("检测尚未完成。"))
        var geoAsn = GeoAsnAssessment(false, false, false, null, listOf("检测尚未完成。"))
        var leak = LeakAssessment(false, "需打开人工复核入口确认 WebRTC", listOf("检测尚未完成。"))
        var latency = emptyList<LatencyTargetResult>()
        var packetLoss: PacketLossSignal? = null
        var stability: StabilitySignal? = null
        var speed: SpeedSignal? = null

        suspend fun emit() = onProgress(progress.snapshot(discovery?.confirmedIp, shouldCancel()))
        suspend fun begin(step: String, message: String) {
            progress.begin(step, message)
            emit()
        }
        suspend fun finish(step: String, state: StepState, message: String = "") {
            progress.finish(step, state, message)
            emit()
        }
        fun partial(message: String) = DetectionBundle(primary, geoSignals, discovery, trace, dns, ipType, geoAsn, leak, NetworkQuality(latency, packetLoss, stability, speed), true, message)
        suspend fun stopIfCancelled(): DetectionBundle? {
            if (!shouldCancel()) return null
            DEFAULT_STEPS.filter { progress.stateOf(it) == StepState.WAITING }.forEach {
                progress.finish(it, StepState.CANCELLED, "用户取消")
            }
            emit()
            return partial("检测已取消，结果不完整")
        }

        begin("获取 IPv4 / IPv6", "正在自动获取当前设备网络出口 IPv4 / IPv6")
        discovery = discoverCurrentIp(config)
        if (discovery?.confirmedIp == null) {
            finish("获取 IPv4 / IPv6", StepState.FAILED, "无法获取出口 IP")
            return@coroutineScope partial("无法获取出口 IP：所有公开接口都请求失败，请检查网络连接、代理或防火墙。")
        }
        finish("获取 IPv4 / IPv6", StepState.SUCCESS, "IPv4：${discovery?.ipv4 ?: "未获取"}，IPv6：${discovery?.ipv6 ?: "未获取"}")
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("多源交叉验证 IP", "正在使用 ip-api.com、ipwhois.io、ifconfig.co 交叉验证")
        trace = fetchCloudflareTrace()
        geoSignals = fetchGeoSignals(discovery?.confirmedIp.orEmpty())
        primary = geoSignals.firstOrNull { it.status == SourceStatus.SUCCESS }
        geoAsn = assessGeoAsn(geoSignals, dns)
        finish("多源交叉验证 IP", if (primary != null) StepState.SUCCESS else StepState.FAILED, primary?.source ?: "多源归属信息获取失败")
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("检测 DNS 信息", "正在读取系统 DNS 并判断是否疑似泄露")
        dns = detectDns(primary)
        geoAsn = assessGeoAsn(geoSignals, dns)
        finish("检测 DNS 信息", if (dns?.error == null) StepState.SUCCESS else StepState.FAILED, dns?.error.orEmpty())
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("判断 IP 类型", "正在根据多源字段、ASN、ISP、组织关键词判断")
        ipType = assessIpType(geoSignals)
        leak = assessLeaks(discovery, geoSignals, dns)
        finish("判断 IP 类型", StepState.SUCCESS, ipType.label)
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("测试延迟", "正在测试延迟")
        latency = runLatencyTests()
        finish("测试延迟", if (latency.any { it.samplesMs.isNotEmpty() }) StepState.SUCCESS else StepState.FAILED)
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("测试请求失败率", "正在测试连接失败率")
        packetLoss = runPacketLossTest()
        finish("测试请求失败率", StepState.SUCCESS, "失败率 ${"%.1f%%".format(packetLoss?.failureRate ?: 0.0)}")
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("测试网络稳定性", "正在测试稳定性")
        if (config.stabilityTestEnabled) {
            stability = runStabilityTest()
            finish("测试网络稳定性", StepState.SUCCESS, stability?.level.orEmpty())
        } else {
            finish("测试网络稳定性", StepState.SKIPPED, "已关闭")
        }
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("测试下载速度", "正在测试下载速度")
        speed = if (config.downloadTestEnabled) runQuickDownloadTest() else SpeedSignal(error = "已关闭")
        finish("测试下载速度", if (speed?.downloadMbps != null) StepState.SUCCESS else StepState.FAILED, speed?.error.orEmpty())
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("测试上传速度", "正在测试上传速度")
        speed = speed?.copy(uploadMbps = runQuickUploadTest().uploadMbps, error = speed?.error)
        finish("测试上传速度", if (speed?.uploadMbps != null) StepState.SUCCESS else StepState.FAILED)
        stopIfCancelled()?.let { return@coroutineScope it }

        begin("生成评分报告", "正在生成 100 分综合评分")
        finish("生成评分报告", StepState.SUCCESS)
        begin("检测完成", "检测完成")
        finish("检测完成", StepState.SUCCESS)

        DetectionBundle(primary, geoSignals, discovery, trace, dns, ipType, geoAsn, leak, NetworkQuality(latency, packetLoss, stability, speed), false, "检测完成")
    }

    private suspend fun discoverCurrentIp(config: SourceConfig): IpDiscoveryResult = coroutineScope {
        val results = listOf(
            async { discoverPlainIp("ifconfig.co IPv4", "https://ipv4.ifconfig.co/ip") },
            async { discoverPlainIp("ifconfig.co IPv6", "https://ipv6.ifconfig.co/ip") },
            async { discoverJsonIp("ip-api.com", "http://ip-api.com/json/?fields=query,status,message", "query") },
            async { discoverPlainIp("ifconfig.co", "https://ifconfig.co/ip") },
            async { discoverJsonIp("ipwhois.io", "https://ipwhois.app/json/", "ip") },
            async { discoverCloudflare() }
        ).awaitAll()
        val valid = results.mapNotNull { it.ip?.trim()?.takeIf(::looksLikeIp) }
        val ipv4 = valid.firstOrNull { isIpv4(it) }
        val ipv6 = valid.firstOrNull { it.contains(":") }?.takeIf { config.ipv6Enabled }
        val preferred = ipv4 ?: ipv6 ?: valid.firstOrNull()
        val distinct = valid.toSet()
        val message = when {
            preferred == null -> "所有公开接口均未返回有效 IP。"
            distinct.size == 1 && valid.size >= 2 -> "多个公开接口返回同一出口 IP，置信度较高。"
            distinct.size == 1 -> "仅一个公开接口确认出口 IP，置信度较低。"
            ipv4 != null && ipv6 != null -> "检测到 IPv4 与 IPv6 并存，需关注 IPv6 是否绕过代理。"
            else -> "多个公开接口返回不一致，可能存在代理链、IPv4/IPv6 差异或网络波动。"
        }
        IpDiscoveryResult(preferred, ipv4, ipv6, results, message, if (distinct.size > 1) message else null)
    }

    private suspend fun fetchGeoSignals(ip: String): List<IpSignal> = coroutineScope {
        listOf(
            async { fetchIpApi(ip) },
            async { fetchIpwhois(ip) },
            async { fetchIfconfig(ip) }
        ).awaitAll()
    }

    private suspend fun fetchIpApi(ip: String): IpSignal =
        safeJson("ip-api.com", "http://ip-api.com/json/$ip?fields=status,message,query,country,countryCode,regionName,city,isp,as,asname,org,timezone,mobile,proxy,hosting") { json ->
            if (json.optString("status") == "fail") error(json.optStringOrNull("message") ?: "ip-api.com 返回失败")
            IpSignal(
                source = "ip-api.com",
                ip = json.optStringOrNull("query") ?: ip,
                country = json.optStringOrNull("country"),
                countryCode = json.optStringOrNull("countryCode"),
                region = json.optStringOrNull("regionName"),
                city = json.optStringOrNull("city"),
                isp = json.optStringOrNull("isp"),
                asn = json.optStringOrNull("as"),
                organization = json.optStringOrNull("org") ?: json.optStringOrNull("asname"),
                timezone = json.optStringOrNull("timezone"),
                isMobile = json.optNullableBoolean("mobile"),
                isProxy = json.optNullableBoolean("proxy"),
                isHosting = json.optNullableBoolean("hosting")
            )
        }

    private suspend fun fetchIpwhois(ip: String): IpSignal =
        safeJson("ipwhois.io", "https://ipwhois.app/json/$ip") { json ->
            if (json.optStringOrNull("success") == "false") error(json.optStringOrNull("message") ?: "ipwhois.io 返回失败")
            IpSignal(
                source = "ipwhois.io",
                ip = json.optStringOrNull("ip") ?: ip,
                country = json.optStringOrNull("country"),
                countryCode = json.optStringOrNull("country_code"),
                region = json.optStringOrNull("region"),
                city = json.optStringOrNull("city"),
                isp = json.optStringOrNull("isp"),
                asn = json.optStringOrNull("asn"),
                organization = json.optStringOrNull("org"),
                timezone = json.optStringOrNull("timezone_name") ?: json.optStringOrNull("timezone")
            )
        }

    private suspend fun fetchIfconfig(ip: String): IpSignal =
        safeJson("ifconfig.co", "https://ifconfig.co/json") { json ->
            IpSignal(
                source = "ifconfig.co",
                ip = json.optStringOrNull("ip") ?: ip,
                country = json.optStringOrNull("country"),
                countryCode = json.optStringOrNull("country_iso"),
                city = json.optStringOrNull("city"),
                asn = json.optStringOrNull("asn"),
                organization = json.optStringOrNull("asn_org"),
                timezone = json.optStringOrNull("time_zone")
            )
        }

    private fun detectDns(primary: IpSignal?): DnsSignal {
        return runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty().map { it.hostAddress.orEmpty() }.filter { it.isNotBlank() }
            val serverText = servers.joinToString(" ")
            val countryCode = when {
                serverText.contains("114.114.") || serverText.contains("223.5.") || serverText.contains("223.6.") -> "CN"
                serverText.contains("8.8.") || serverText.contains("1.1.") || serverText.contains("9.9.") -> "US"
                else -> null
            }
            val isp = when {
                serverText.contains("114.114.") -> "中国大陆运营商公共 DNS"
                serverText.contains("223.5.") || serverText.contains("223.6.") -> "阿里公共 DNS"
                serverText.contains("8.8.") -> "Google DNS"
                serverText.contains("1.1.") -> "Cloudflare DNS"
                serverText.contains("9.9.") -> "Quad9 DNS"
                else -> null
            }
            val ipCountry = primary?.countryCode
            val leak = countryCode != null && ipCountry != null && !countryCode.equals(ipCountry, true) ||
                (ipCountry.equals("US", true) && countryCode.equals("CN", true))
            val evidence = mutableListOf<String>()
            evidence += if (servers.isEmpty()) "系统未暴露 DNS 服务器地址，无法自动判断 DNS 国家。" else "系统 DNS 服务器：${servers.joinToString()}"
            if (countryCode != null) evidence += "DNS 服务器命中特征国家：$countryCode，依据是常见公共 DNS 地址段。"
            if (ipCountry != null && countryCode != null) evidence += "出口 IP 国家为 $ipCountry，DNS 国家为 $countryCode。"
            if (ipCountry.equals("US", true) && countryCode.equals("CN", true)) evidence += "出口 IP 为美国但 DNS 是中国大陆运营商，疑似 DNS 泄露。"
            DnsSignal(servers, countryCode, countryCode, null, isp, isp, leak, countryCode == "CN" && ipCountry != "CN", evidence)
        }.getOrElse { DnsSignal(error = it.message ?: "DNS 信息读取失败", evidence = listOf("DNS 信息读取失败：${it.message ?: "未知错误"}")) }
    }

    private fun assessIpType(signals: List<IpSignal>): IpTypeAssessment {
        val ok = signals.filter { it.status == SourceStatus.SUCCESS }
        val text = ok.joinToString(" ") { "${it.isp.orEmpty()} ${it.organization.orEmpty()} ${it.asn.orEmpty()}" }.lowercase()
        val evidence = mutableListOf<String>()
        ok.forEach { signal ->
            evidence += "${signal.source}：ISP=${signal.isp ?: "未知"}，ASN=${signal.asn ?: "未知"}，组织=${signal.organization ?: "未知"}，mobile=${signal.isMobile ?: "未知"}，proxy=${signal.isProxy ?: "未知"}，hosting=${signal.isHosting ?: "未知"}。"
        }
        val label = when {
            ok.any { it.isProxy == true } || listOf("vpn", "proxy", "privacy", "relay").any { text.contains(it) } -> "疑似代理 / VPN IP"
            ok.any { it.isHosting == true } || listOf("hosting", "cloud", "data center", "datacenter", "server", "vps", "amazon", "google cloud", "azure", "oracle", "digitalocean", "linode", "hetzner", "ovh").any { text.contains(it) } -> "疑似云服务器 IP"
            listOf("colo", "telehouse", "datacamp", "m247").any { text.contains(it) } -> "疑似机房 IP"
            ok.any { it.isMobile == true } || listOf("mobile", "cellular", "wireless", "5g", "4g", "lte").any { text.contains(it) } -> "疑似移动网络"
            listOf("broadband", "fiber", "fibre", "cable", "telecom", "comcast", "verizon", "att", "unicom", "chinanet").any { text.contains(it) } -> "疑似住宅 IP"
            else -> "无法判断"
        }
        val confidence = when {
            ok.size >= 3 && label != "无法判断" -> 85
            ok.size >= 2 && label != "无法判断" -> 70
            ok.size >= 1 && label != "无法判断" -> 55
            else -> 20
        }
        if (ok.isEmpty()) evidence += "三个无 Key 公开接口都未返回有效归属信息。"
        return IpTypeAssessment(label, confidence, evidence)
    }

    private fun assessGeoAsn(signals: List<IpSignal>, dns: DnsSignal?): GeoAsnAssessment {
        val ok = signals.filter { it.status == SourceStatus.SUCCESS }
        val countries = ok.mapNotNull { it.countryCode ?: it.country }.map { it.lowercase() }.toSet()
        val cities = ok.mapNotNull { it.city }.map { it.lowercase() }.toSet()
        val orgText = ok.joinToString(" ") { "${it.isp.orEmpty()} ${it.organization.orEmpty()} ${it.asn.orEmpty()}" }.lowercase()
        val asnMatches = ok.any { !it.asn.isNullOrBlank() && (!it.isp.isNullOrBlank() || !it.organization.isNullOrBlank()) } &&
            !listOf("unknown", "reserved", "private").any { orgText.contains(it) }
        val ipCountry = ok.firstOrNull()?.countryCode
        val dnsConsistent = if (ipCountry != null && dns?.countryCode != null) ipCountry.equals(dns.countryCode, true) else null
        val evidence = mutableListOf<String>()
        evidence += "国家结果：${ok.joinToString { "${it.source}=${it.countryCode ?: it.country ?: "未知"}" }}"
        evidence += "城市结果：${ok.joinToString { "${it.source}=${it.city ?: "未知"}" }}"
        evidence += "ASN/ISP：${ok.joinToString { "${it.source}=${it.asn ?: "未知"}/${it.isp ?: it.organization ?: "未知"}" }}"
        evidence += "IP 国家和 DNS 国家：${ipCountry ?: "未知"} / ${dns?.countryCode ?: "未知"}"
        return GeoAsnAssessment(countries.size <= 1 && ok.size >= 2, cities.size <= 1 && ok.size >= 2, asnMatches, dnsConsistent, evidence)
    }

    private fun assessLeaks(discovery: IpDiscoveryResult?, signals: List<IpSignal>, dns: DnsSignal?): LeakAssessment {
        val evidence = mutableListOf<String>()
        val hasV4 = discovery?.ipv4 != null
        val hasV6 = discovery?.ipv6 != null
        val ipType = assessIpType(signals).label
        val v6Leak = hasV4 && hasV6 && (ipType.contains("代理") || ipType.contains("VPN") || ipType.contains("云服务器") || dns?.leakSuspected == true)
        evidence += "IPv4：${discovery?.ipv4 ?: "未获取到"}；IPv6：${discovery?.ipv6 ?: "未获取到"}。"
        if (v6Leak) evidence += "IPv4 可能走代理或云节点，同时检测到 IPv6 出口，需确认 IPv6 是否暴露真实运营商。"
        evidence += "WebRTC 无法在 App 内可靠读取浏览器候选地址，请使用内置 BrowserLeaks / ipleak.net 入口人工复核。"
        return LeakAssessment(v6Leak, "需人工复核", evidence)
    }

    private suspend fun discoverCloudflare(): IpSourceResult = withContext(Dispatchers.IO) {
        runCatching { IpSourceResult("Cloudflare Trace", fetchTrace()["ip"]) }
            .getOrElse { IpSourceResult("Cloudflare Trace", status = SourceStatus.FAILED, error = it.message ?: "失败") }
    }

    private suspend fun discoverJsonIp(source: String, url: String, field: String): IpSourceResult = withContext(Dispatchers.IO) {
        runCatching { IpSourceResult(source, JSONObject(getText(url)).optStringOrNull(field)) }
            .getOrElse { IpSourceResult(source, status = SourceStatus.FAILED, error = it.message ?: "失败") }
    }

    private suspend fun discoverPlainIp(source: String, url: String): IpSourceResult = withContext(Dispatchers.IO) {
        runCatching { IpSourceResult(source, getText(url).trim()) }
            .getOrElse { IpSourceResult(source, status = SourceStatus.FAILED, error = it.message ?: "失败") }
    }

    private suspend fun runLatencyTests(): List<LatencyTargetResult> = coroutineScope {
        latencyTargets.map { target ->
            async {
                val samples = mutableListOf<Long>()
                var failures = 0
                repeat(5) {
                    val ms = httpProbe(target)
                    if (ms == null) failures++ else samples += ms
                    delay(250)
                }
                LatencyTargetResult(target, samples, failures)
            }
        }.awaitAll()
    }

    private suspend fun runPacketLossTest(): PacketLossSignal {
        val target = "https://cp.cloudflare.com/generate_204"
        val samples = mutableListOf<Long>()
        var failures = 0
        repeat(20) {
            val ms = httpProbe(target)
            if (ms == null) failures++ else samples += ms
            delay(200)
        }
        return PacketLossSignal(target, 20, failures, samples.takeIf { it.isNotEmpty() }?.average())
    }

    private suspend fun runStabilityTest(): StabilitySignal {
        val target = "https://cp.cloudflare.com/generate_204"
        val attempts = 15
        val samples = mutableListOf<Long>()
        var failures = 0
        var consecutive = 0
        var maxConsecutive = 0
        repeat(attempts) {
            val ms = httpProbe(target)
            if (ms == null) {
                failures++
                consecutive++
                maxConsecutive = maxOf(maxConsecutive, consecutive)
            } else {
                samples += ms
                consecutive = 0
            }
            delay(1200)
        }
        val successRate = (attempts - failures) * 100.0 / attempts
        val jitter = calculateJitter(samples)
        val level = when {
            successRate >= 98 && maxConsecutive == 0 && (jitter ?: 0.0) < 80 -> "稳定"
            successRate >= 90 && maxConsecutive <= 1 -> "基本稳定"
            successRate >= 75 -> "波动明显"
            else -> "不稳定"
        }
        return StabilitySignal(target, attempts, attempts - failures, samples.takeIf { it.isNotEmpty() }?.average(), samples.maxOrNull(), jitter, maxConsecutive, level)
    }

    private suspend fun runQuickDownloadTest(): SpeedSignal = withContext(Dispatchers.IO) {
        runCatching {
            val bytesToDownload = 5_000_000
            val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=$bytesToDownload").header("Cache-Control", "no-cache").build()
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val bytes = response.body?.bytes()?.size ?: 0
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000.0).roundToLong().coerceAtLeast(1)
                SpeedSignal(downloadMbps = bytes * 8.0 / elapsedMs / 1000.0, elapsedMs = elapsedMs, bytes = bytes.toLong())
            }
        }.getOrElse { SpeedSignal(error = it.message ?: "下载测速失败") }
    }

    private suspend fun runQuickUploadTest(): SpeedSignal = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ByteArray(1_000_000) { 1 }
            val body = bytes.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder().url("https://speed.cloudflare.com/__up").post(body).header("Cache-Control", "no-cache").build()
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000.0).roundToLong().coerceAtLeast(1)
                SpeedSignal(uploadMbps = bytes.size * 8.0 / elapsedMs / 1000.0, elapsedMs = elapsedMs, bytes = bytes.size.toLong())
            }
        }.getOrElse { SpeedSignal(error = it.message ?: "上传测速失败") }
    }

    private suspend fun httpProbe(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("Cache-Control", "no-cache").header("User-Agent", "NetworkRiskTester/NoKey Android").build()
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 204) error("HTTP ${response.code}")
                ((System.nanoTime() - start) / 1_000_000.0).roundToLong().coerceAtLeast(1)
            }
        }.getOrNull()
    }

    private suspend fun fetchCloudflareTrace(): CloudflareTrace = withContext(Dispatchers.IO) {
        runCatching {
            val map = fetchTrace()
            CloudflareTrace(map["ip"], map["colo"], map["loc"], map["tls"], map["http"], map)
        }.getOrElse { CloudflareTrace(error = it.message ?: "Cloudflare trace 请求失败") }
    }

    private suspend fun safeJson(source: String, url: String, mapper: (JSONObject) -> IpSignal): IpSignal = withContext(Dispatchers.IO) {
        runCatching { mapper(JSONObject(getText(url))).copy(status = SourceStatus.SUCCESS) }
            .getOrElse { IpSignal(source = source, status = SourceStatus.FAILED, error = it.message ?: "失败") }
    }

    private fun fetchTrace(): Map<String, String> = getText("https://www.cloudflare.com/cdn-cgi/trace")
        .lineSequence()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }
        .toMap()

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "NetworkRiskTester/NoKey Android").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun looksLikeIp(value: String): Boolean = value.contains(".") || value.contains(":")

    private fun isIpv4(ip: String): Boolean = runCatching { InetAddress.getByName(ip).hostAddress?.contains(".") == true }.getOrDefault(ip.contains("."))

    companion object {
        private val latencyTargets = listOf(
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://www.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}

private class ProgressTracker {
    private val states = DEFAULT_STEPS.associateWith { DetectionStepProgress(it) }.toMutableMap()
    fun begin(step: String, message: String) { states[step] = DetectionStepProgress(step, StepState.RUNNING, message) }
    fun finish(step: String, state: StepState, message: String = "") { states[step] = DetectionStepProgress(step, state, message) }
    fun stateOf(step: String): StepState = states[step]?.state ?: StepState.WAITING
    fun snapshot(confirmedIp: String?, cancelled: Boolean): DetectionProgress {
        val steps = DEFAULT_STEPS.map { states[it] ?: DetectionStepProgress(it) }
        val completed = steps.count { it.state !in listOf(StepState.WAITING, StepState.RUNNING) }
        val current = steps.firstOrNull { it.state == StepState.RUNNING }?.message ?: if (completed == steps.size) "检测完成" else ""
        return DetectionProgress(current, completed, steps.size, steps, confirmedIp, cancelled)
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() && it != "null" && it != "-" } else null
private fun JSONObject.optNullableBoolean(name: String): Boolean? = if (has(name) && !isNull(name)) optBoolean(name) else null
