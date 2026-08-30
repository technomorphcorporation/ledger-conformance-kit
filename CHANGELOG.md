# Changelog

Semantic versioning, with `lck-spi` treated as the client contract.

- **MAJOR** — a breaking change to `LedgerAdapter`, `Model` or `Capability`.
  Preceded by at least two MINOR releases of deprecation.
- **MINOR** — new invariants, adapters, report formats. **A new invariant can turn your
  build red**; use `--baseline` to adopt deliberately.
- **PATCH** — fixes to existing invariants, reporting, documentation.

## [Unreleased]

### Added
- Fourteen invariants: INV-01 through INV-14
- Adapter TCK: nine checks that verify an adapter before any finding is believed
- Mutation check: six seeded defects asserting the suite has no false negatives, and a
  known-correct ledger asserting it has no false positives
- Ratchet mode (`--baseline`) so teams with existing failures can adopt incrementally
- Reports: HTML trial balance, JSON, SARIF, JUnit XML
- HTTP adapter contract for non-JVM ledgers
- `complianceCheck`: build-enforced zero-dependency and no-telemetry assertions
