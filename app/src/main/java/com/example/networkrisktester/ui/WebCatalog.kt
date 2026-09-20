package com.example.networkrisktester.ui

data class CheckSite(
    val category: String,
    val title: String,
    val url: String
)

val checkSites = listOf(
    CheckSite("DNS泄露", "ipleak.net", "https://ipleak.net/"),
    CheckSite("DNS泄露", "BrowserLeaks DNS", "https://browserleaks.com/dns"),
    CheckSite("DNS泄露", "DNSLeakTest", "https://www.dnsleaktest.com/"),
    CheckSite("WebRTC泄露", "BrowserLeaks WebRTC", "https://browserleaks.com/webrtc"),
    CheckSite("WebRTC泄露", "ipleak.net", "https://ipleak.net/"),
    CheckSite("网速测试", "Cloudflare Speed Test", "https://speed.cloudflare.com/"),
    CheckSite("网速测试", "Fast.com", "https://fast.com/"),
    CheckSite("网速测试", "Speedtest.net", "https://www.speedtest.net/")
)
