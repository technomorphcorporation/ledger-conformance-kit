# Security policy

## Reporting a vulnerability

Email **security@technomorph.tech** with `[LCK]` in the subject, or use GitHub private
vulnerability reporting. Please do not open a public issue.

- Acknowledgement within **2 business days**
- Assessment and severity within **10 business days**
- Fix or documented mitigation within **90 days**, sooner for anything exploitable
- Credit in the advisory unless you prefer otherwise

## Scope

**In scope:** anything in this repository — the harness, the adapters, the build, the
release pipeline.

**Out of scope:** findings the kit reports about *your* ledger. A red scorecard is a defect
in the system under test, not a vulnerability in this project. Report those to whoever owns
that system.

## Threat model

This is a test harness that runs in CI against a scratch environment. Three properties are
guaranteed and enforced by `./gradlew complianceCheck`:

1. **No network egress** beyond the adapter endpoint the operator configures. No telemetry,
   no analytics, no update check, no licence call. Runs air-gapped.
2. **No production data**, structurally: the adapter contract cannot express a read of
   pre-existing records, and `AdapterTck` TCK-09 refuses a non-empty environment.
3. **Zero runtime dependencies** in `lck-spi` and `lck`.

Full detail for a third-party review: [COMPLIANCE.md](COMPLIANCE.md).

## Supply chain

Releases are GPG-signed and Sigstore-signed, published to Maven Central with SHA-256
checksums and a CycloneDX SBOM. Builds are reproducible. The Gradle wrapper is pinned and
validated in CI.
