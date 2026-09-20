# NetworkRiskTester Handoff

## Current State

- Android network exit/IP inspection tool using public no-key services.
- It reports IPv4/IPv6, ASN/ISP/location, DNS/WebRTC review links, connection quality, and a local risk score; it is not a commercial blacklist checker.
- Public repository: https://github.com/matt-xb/network-risk-tester
- Gradle caches, IDE state, build output, and `local.properties` are excluded.
- No open-source license has been selected yet; public visibility alone does not grant reuse rights.

## Verification

- Source snapshot uploaded on 2026-09-20.
- Android build and live endpoint checks were not rerun during repository organization.
- GitHub prerelease `v0.1.0` contains `network-risk-tester-v0.1.0-debug.apk` (10,711,591 bytes, SHA-256 `0b850830e8ecf0ba1a80b142092eb480c48064c071a16e7d9b3cc45b990cdc14`).
- APK metadata confirms application ID `com.example.networkrisktester`, version `0.1.0`, min SDK 26, target SDK 35; APK Signature Scheme v2 verification passes with the Android Debug certificate.

## Next Step

- Build the debug variant and verify behavior on Wi-Fi, mobile data, IPv4-only, and IPv6-capable networks.
- Select and add an open-source license before inviting external reuse or contributions.
