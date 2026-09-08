# Changelog

Semantic versioning, with `lck-spi` treated as the client contract.

- **MAJOR** — a breaking change to `LedgerAdapter`, `Model` or `Capability`.
  Preceded by at least two MINOR releases of deprecation.
- **MINOR** — new invariants, adapters, report formats. **A new invariant can turn your
  build red**; use `--baseline` to adopt deliberately.
- **PATCH** — fixes to existing invariants, reporting, documentation.

## [Unreleased] — 1.1.0

### Added
- **INV-15** — a duplicate of a refused transaction is not reported as applied. A ledger
  whose idempotency record has two states instead of three tells a concurrent caller its
  payment already succeeded, then withdraws the claim when the transaction turns out to be
  refused. Nothing is written and the retry stops. **This can turn your build red**: it is a
  real defect that silently loses payments, and every existing invariant passes a ledger that
  has it. See `rfcs/0001`. Adopt with `--baseline` if you need to.

### Fixed
- The JUnit integration reported a failed adapter TCK by aborting all invariants, and the
  abort message is dropped by at least one common runner — so a non-conformant adapter
  produced ignored tests and no reason anywhere in the output. There is now an `adapter TCK`
  test that fails, listing every finding.

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
