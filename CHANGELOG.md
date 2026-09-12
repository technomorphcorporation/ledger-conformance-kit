# Changelog

Semantic versioning, with `lck-spi` treated as the client contract.

- **MAJOR** — a breaking change to `LedgerAdapter`, `Model` or `Capability`.
  Preceded by at least two MINOR releases of deprecation.
- **MINOR** — new invariants, adapters, report formats. **A new invariant can turn your
  build red**; use `--baseline` to adopt deliberately.
- **PATCH** — fixes to existing invariants, reporting, documentation.

## [Unreleased]

### Fixed
- The signing key is now published to `keys.openpgp.org` as well as `keyserver.ubuntu.com`, and
  the documented verification command is confirmed to work against it from an empty keyring.
  Until now the key was absent there, and an absent key on that host does not fail cleanly: the
  server answers 200 with every user ID stripped, and `gpg` refuses to import a key with no user
  ID while reporting `Total number processed: 1`. A reviewer following the instructions would
  have seen what looked like a successful fetch, then `No public key` on the verify — which
  reads as a bogus signature rather than a missing key. `SECURITY.md` also now explains the
  `not certified with a trusted signature` warning, which is expected output for any freshly
  fetched key and not a defect in the release.
- **A stale supply-chain claim.** `SECURITY.md`, `COMPLIANCE.md` and the README all still said
  release signing was configured but nothing had been published — untrue since 1.0.0, and in the
  one section written for the reader most likely to check it. All three now state what is true:
  every artifact of all three modules, at 1.0.0 and 1.1.0, is GPG-signed by a named fingerprint,
  with a command to verify it against the keyserver copy rather than against our word. The two
  real gaps are stated rather than left to be found — the key is absent from `keys.openpgp.org`,
  and there is still no SBOM or Sigstore attestation, so the signature attests to the publisher
  and not to the build.

### Documentation
- Four points from external review, in `docs/01-core-topics.md`: when `IN_FLIGHT` is actually
  observable and why a sweep must establish the outcome rather than merely expire the claim;
  eviction as a correctness parameter; how the conditional-write pattern maps to DynamoDB,
  Cassandra, MySQL and Redis, and the multi-partition constraint that does not map; and the
  distinction between a read that decides and a read that explains, which is why "zero rows
  updated" stops meaning insufficient funds as soon as the predicate carries a second rule.
- `REVIEWERS.md`, covering what a review of a new invariant is for, and crediting review that
  has changed the kit.
- INV-15 is now cited in the documents that describe the rule it enforces. `01-core-topics`
  states the three-state idempotency rule and cited only INV-04 and INV-05, neither of which
  can see it — both fund the account first, so the transaction under test always succeeds.
  `02-architectures` shows the code that gets it right; `adapter-guide` now maps every
  capability to the invariants it unlocks.

## [1.1.0] — 2026-09-09

### Added
- **INV-15** — a duplicate of a refused transaction is not reported as applied. A ledger
  whose idempotency record has two states instead of three tells a concurrent caller its
  payment already succeeded, then withdraws the claim when the transaction turns out to be
  refused. Nothing is written and the retry stops. **This can turn your build red**: it is a
  real defect that silently loses payments, and every existing invariant passes a ledger that
  has it. See `rfcs/0001`. Adopt with `--baseline` if you need to.

- A worked PostgreSQL example, the same ledger wrong and then right, run against a real
  database with Testcontainers. The article's three fixes — a unique constraint rather than a
  query, the sufficient-funds predicate inside the `UPDATE`, and a three-state idempotency
  record — are database behaviour and cannot be demonstrated against an in-memory structure,
  which has no isolation level at all. Test-only: the published jars still carry no
  dependencies. Skips rather than fails without Docker.

### Changed
- **The JUnit integration now reports sixteen tests, not fourteen** — an `adapter TCK` check
  followed by fifteen invariants. If you assert on a test count or watch one on a dashboard,
  this is the release that changes it.
- `complianceCheck` asserts the `api` chain that makes the documented one-coordinate install
  work. Adding `lck-junit5` brings `lck-spi` with it, and the build now fails if a future edit
  turns that `api` into `implementation` and quietly breaks every consumer's compile.

### Fixed
- The JUnit integration reported a failed adapter TCK by aborting all invariants, and the
  abort message is dropped by at least one common runner — so a non-conformant adapter
  produced ignored tests and no reason anywhere in the output. There is now an `adapter TCK`
  test that fails, listing every finding.

### Documentation
- Published as a site at
  [technomorphcorporation.github.io/ledger-conformance-kit](https://technomorphcorporation.github.io/ledger-conformance-kit/),
  served from `docs/` on `main`, so a page is versioned with the code it describes and reviewed
  in the same pull request.
- The install instructions said which artifact to add but not where the adapter goes. An
  adapter in `src/main` did not compile against a single `lck-junit5` dependency; it belongs in
  the test source set, and now says so — `reset()` empties the ledger, and code that can
  truncate a schema has no business on a production classpath.

## [1.0.0] — 2026-09-08

### Added
- Fourteen invariants: INV-01 through INV-14
- Adapter TCK: nine checks that verify an adapter before any finding is believed
- Mutation check: a seeded defect for every invariant, each asserting the exact set it trips,
  and a known-correct ledger asserting the suite has no false positives
- Ratchet mode (`--baseline`) so teams with existing failures can adopt incrementally
- Reports: HTML trial balance, JSON, SARIF, JUnit XML
- HTTP adapter contract for non-JVM ledgers
- `complianceCheck`: build-enforced zero-dependency and no-telemetry assertions
