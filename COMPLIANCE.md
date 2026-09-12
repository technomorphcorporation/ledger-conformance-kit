# Compliance & technology risk

Written for a third-party software review at a bank or a regulated fintech. Every claim
here is asserted by the build (`./gradlew complianceCheck`) rather than promised in prose.

---

## 1. What this software is

A **test harness**. It exercises a ledger you point it at and reports which correctness
properties held. It is not middleware, it is not deployed, and it does not run in
production. Its blast radius is a CI job and a scratch database schema.

## 2. Data handling

| Question | Answer |
|---|---|
| Does it read production data? | **Not if pointed at a scratch environment.** `journal()` returns whatever the ledger holds, so this is enforced by TCK-00 and by where you point the adapter, not by the shape of the contract. `reset()` is mandatory and the suite creates every account it uses |
| Does it store anything? | Only report files, in a directory you specify. No database, no cache, no state between runs |
| Does it transmit anything? | **No.** The only outbound connection is to the adapter endpoint you configure |
| PII / customer data? | Never touched. Account identifiers in the suite are synthetic (`acct:a`, `external:funding`) |
| Where do reports go? | `build/reports/lck/` by default. Local filesystem, nowhere else |

**Safety net.** `AdapterTck` check TCK-00 reads the journal *before* any check that writes,
and aborts the whole run if it is not already empty. It runs first precisely because every
other check begins with `reset()`: a scratch-environment check placed after them could only
confirm the wipe it existed to prevent. This is a backstop against misconfiguration, not a
licence to point it at production.

## 3. Network behavior

- **No telemetry.** No analytics, no usage reporting, no crash reporting.
- **No update check.** No call home on start, ever.
- **No licence validation.** Apache-2.0; there is nothing to validate.
- **Runs air-gapped.** Once artifacts are mirrored internally, the tool needs no internet.

Enforced by `complianceCheck`, which greps the main sources for telemetry-shaped
references and fails the build on a match. The only HTTP client in the codebase is
`HttpLedgerAdapter`, which connects solely to the base URL passed by the operator.

## 4. Dependencies

| Module | Runtime dependencies |
|---|---|
| `lck-spi` | **none** |
| `lck` | **none** (JDK only, including the HTTP adapter via `java.net.http`) |
| `lck-junit5` | `junit-jupiter-api` only, and only if you use the JUnit integration |

Three modules, so a reviewer has three POMs to read rather than nine.

**`lck-spi` is the only artifact your engineers compile against.** It has zero
dependencies and targets Java 17, so a team on 17 can implement an adapter and run the
suite on a 21 toolchain in CI.

The zero-dependency rule is a design constraint, not an accident. A test tool that drags
two hundred transitive jars onto a classpath fails dependency review on CVE exposure
before anyone reads what it does. It is also impossible to retrofit.

Full inventory: `gradle/libs.versions.toml` — short enough to read in full, which is the point.

## 5. Supply chain

- Apache-2.0 throughout. **No copyleft anywhere in the tree.**
- Reproducible builds: jar timestamps normalized, file order fixed. Configured in
  `build.gradle.kts` and true of any build you run today.
- Gradle wrapper pinned by SHA-256 (`distributionSha256Sum` in
  `gradle/wrapper/gradle-wrapper.properties`), so a swapped distribution fails the build
  rather than executing. `gradle/actions/wrapper-validation` checks `gradle-wrapper.jar`
  against Gradle's published checksums on every push, so a jar swapped in a pull request
  cannot execute in CI.
- Every push and pull request runs `./gradlew verify` — the full test suite, the mutation
  corpus and `complianceCheck` — plus a second run on a different seed and the demo against
  both example ledgers. See `.github/workflows/ci.yml`.
- A worked PostgreSQL example runs separately, on `main` and nightly, because it needs Docker
  and takes minutes. It is not a merge gate. See `.github/workflows/postgres.yml`.
- Third-party GitHub Actions are pinned to commit SHAs rather than tags. A tag is mutable,
  and pinning to one would be a supply-chain claim about someone else's repository that this
  project is in no position to make.
- Releases are tag-triggered and staged. `.github/workflows/release.yml` runs the full gate,
  builds a GPG-signed bundle, asserts every artifact and signature is present, and uploads to
  the Sonatype Central Publisher Portal — where it stops. The final publish is a manual step,
  because a version on Central cannot be withdrawn and an irreversible action should be a
  decision rather than a consequence of pushing a tag. See `docs/releasing.md`.
- No third-party Gradle plugin is used to publish. The bundle is assembled by the built-in
  `maven-publish` and uploaded with `curl`, so the build that produces a zero-dependency
  artifact does not itself pull in a dependency tree to do it.

1.0.0 and 1.1.0 are published, so the signing key, the upload and the Portal's validation are
no longer untested claims: every artifact of all three modules carries a `.asc` that verifies
against key `0DCF 5D5C 8A37 A283 54F9  CAB1 C50B AAE0 1537 C1B3`, which is published on
both `keys.openpgp.org` and `keyserver.ubuntu.com`.

**Not yet in place, and listed here rather than claimed above:** there is no CycloneDX SBOM and
no Sigstore attestation, which means the signature attests to the publisher and not to the
build. These are tracked in `ROADMAP.md` and this section will grow as they land.

## 6. Vulnerability management

See `SECURITY.md`. Summary: private disclosure via `security@technomorph.tech` or GitHub
private reporting; acknowledgement within 2 business days; assessment within 10; fix or
documented mitigation within 90 days, faster for anything exploitable.

**Out of scope:** findings the kit reports about *your* ledger. A red scorecard is a
defect in the system under test, not a vulnerability in this project.

## 7. Support and continuity

Honest statement of the current position, because bus factor is the standard and fair
objection to a young open-source project:

- **Today:** maintained by Technomorph Corporation. Response policy in `SECURITY.md`.
- **Commercial support** with contractual response times is available separately;
  engagement conduct is governed by a published [publication protocol](docs/publication-protocol.md).
- **Intended:** contribution to a neutral foundation home once v1.0 has a track record,
  which would place governance and continuity outside any single maintainer.

`lck-spi` carries a stability guarantee: your adapter will not break on a MINOR or PATCH
upgrade. New invariants arrive in MINOR releases and *can* turn a build red — use
`--baseline` to adopt them deliberately.

## 8. Operational profile

| Property | Value |
|---|---|
| Runtime | JVM 21 (toolchain); `lck-spi` bytecode targets 17 |
| Typical run | under 90 seconds for the full suite |
| Concurrency | up to 500 virtual threads; tune with `--concurrency` |
| Memory | 2 GB heap is generous |
| Privileges | none. Runs as an ordinary CI user |
| Failure mode | non-zero exit equal to the count of BLOCKER regressions |

## 9. What this is not

State these plainly to anyone who might over-read a clean result:

- **Not a certification.** It reports which invariants held, on the system as configured,
  on the day it ran. There is no grade, no score, no percentage — deliberately.
- **Not an assurance engagement** and not a regulatory opinion.
- **Not a test of your accounting model.** It cannot tell you whether your chart of
  accounts matches your business. No tool can.
- **Not a security or performance test.**

A clean run is evidence about specific properties. Representing it as more than that
damages the client and the tool equally.
