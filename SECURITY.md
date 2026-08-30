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

This is a test harness that runs against a scratch environment. Two properties are enforced
by `./gradlew complianceCheck`, and a third depends on how you deploy it:

1. **No network egress** beyond the adapter endpoint the operator configures. No telemetry,
   no analytics, no update check, no licence call. Runs air-gapped. The build fails on a
   telemetry-shaped reference in any source file, and on any remote asset — a webfont, a
   script, a stylesheet — referenced by output the kit emits.
2. **Zero runtime dependencies** in `lck-spi` and `lck`.
3. **No production data** — this one is a deployment property, not a structural one.
   `journal()` returns whatever the ledger holds, so what protects you is where you point
   the adapter. `AdapterTck` TCK-00 reads the journal before any check writes and refuses
   to continue if it is not already empty, which turns a misconfiguration into a refusal
   instead of a wipe.

Full detail for a third-party review: [COMPLIANCE.md](COMPLIANCE.md).

## Supply chain

Builds are reproducible: jar timestamps are normalized and file order is fixed. The Gradle
wrapper is pinned by SHA-256, so a swapped distribution fails the build rather than running.
Release signing is configured (GPG, in-memory key) but nothing has been published yet — there
is no Maven Central release, no SBOM and no Sigstore attestation. `COMPLIANCE.md` lists what
is in place and what is not, and this section will grow as those land.
