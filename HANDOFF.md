# NetworkRiskTester Handoff

## Current State

- Android network exit/IP inspection tool using public no-key services.
- It reports IPv4/IPv6, ASN/ISP/location, DNS/WebRTC review links, connection quality, and a local risk score; it is not a commercial blacklist checker.
- Private repository: https://github.com/matt-xb/network-risk-tester
- Gradle caches, IDE state, build output, and `local.properties` are excluded.

## Verification

- Source snapshot uploaded on 2026-09-20.
- Android build and live endpoint checks were not rerun during repository organization.

## Next Step

- Build the debug variant and verify behavior on Wi-Fi, mobile data, IPv4-only, and IPv6-capable networks.
