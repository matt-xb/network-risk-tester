# NetworkRiskTester

## 版本与验收状态

截至 2026-10-06，源码为 **0.1.0（code 1）**；[v0.1.0 调试 APK](https://github.com/matt-xb/network-risk-tester/releases/tag/v0.1.0) 为预发布版。交接见 [HANDOFF.md](HANDOFF.md)。

源码构建需要 JDK 17、Android SDK 35，最低 Android 8.0（API 26）。在 Android Studio 配置本机 SDK 后运行 `.\gradlew.bat assembleDebug`，输出 `app/build/outputs/apk/debug/app-debug.apk`。

风险分来自本地规则和第三方接口，是检测线索；不同接口可能冲突，不能单凭分数认定 IP 信誉。此前 APK 元数据检查不代表本轮已完成联网、DNS/WebRTC 或真机验收。仓库尚未选定开源许可证。

不依赖 API Key 的网络 IP 体检工具。

## 定位

- 不让用户填写 IP。
- 不要求用户填写 API Key。
- 不接入 AbuseIPDB、IPQualityScore、Project Honey Pot、MaxMind、IP2Proxy、IP2Location API 等高级信誉库。
- 不做黑名单 API 检测。
- 默认检测当前设备网络出口 IP。
- UI 以“一键检测”为主。

## 自动检测

App 会自动获取当前设备公网 IPv4 / IPv6，并使用无 API Key 的公开接口交叉验证：

- ip-api.com
- ipwhois.io
- ifconfig.co
- Cloudflare Trace

报告展示：

- 当前 IPv4 / IPv6
- IP、国家、城市、时区、ASN、ISP、组织名称
- IP 类型判断和判断依据
- DNS 是否疑似泄露及依据
- IPv6 是否疑似泄露
- 延迟、下载速度、上传速度、连接失败率、稳定性评分
- 地理位置与 ASN 匹配度
- 100 分综合评分和风险等级
- 一键复制完整检测报告

## 人工复核入口

App 只提供无 Key 的 DNS / WebRTC 人工复核入口：

- https://ipleak.net
- https://browserleaks.com/dns
- https://www.dnsleaktest.com
- https://browserleaks.com/webrtc

这些页面会自动检测当前浏览器网络环境。
