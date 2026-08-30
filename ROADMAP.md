# Roadmap

Each version is a complete, useful thing. The rule is that nothing from a later version
gets smuggled into an earlier one — see `CLAUDE.md`.

## v1.0 — evidence (current)

Fourteen invariants, the adapter TCK, the mutation check, ratchet mode, four report
formats, the HTTP contract, build-enforced compliance gates. The claim it supports:
*your ledger held these properties under concurrency, retry and replay, on this date.*

Remaining before tagging: worked Postgres adapter as the second known-correct
implementation, GitHub Action, `REVIEWERS.md`, OpenAPI spec for the HTTP contract.

## v1.1 — adoption

Reduce the only real friction, which is writing the adapter.

- Pre-built adapters for common ledger platforms
- Adapter generator: `lck new-adapter --kind jdbc|http|jvm`
- GitHub Action and a GitLab CI template
- Soak mode: `--duration 30m --concurrency 512`, random seed, for the schedules a
  90-second PR run never reaches
- `REVIEWERS.md` and the `rfcs/` process for new invariants

## v2.0 — determinism

The differentiator, and the work most likely to produce a paper.

- **Fault injection**: process kill mid-commit, network partition between the service and
  its database, clock skew, connection-pool exhaustion
- **Deterministic simulation**: a seeded scheduler that reproduces an exact interleaving.
  The difference between "we found this sometimes" and "run seed 8823714 and watch it
  happen every time". Prior art: FoundationDB, TigerBeetle, Antithesis
- **Serializability checking** in the Elle mould — analyse the transaction dependency
  graph rather than assert a fixed property list. Finds anomalies nobody thought to encode

## v2.1 — agentic mandates

Agent-initiated payments are becoming ordinary infrastructure, and an agent is a client
that retries aggressively, runs at machine speed, and may be running in parallel with
copies of itself. That is INV-04 and INV-05 an order of magnitude harder, and a spending
mandate is INV-09 wearing a different hat.

- **INV-15** Mandate limits hold under concurrent agent spend
- **INV-16** A revoked mandate takes effect before the next commit, not eventually
- **INV-17** Agent-initiated reversals are compensation, never deletion
- **INV-18** Delegated authority is traceable from journal entry back to the human mandate

`Capability.AGENT_MANDATE` is already reserved in the SPI so this lands without a MAJOR bump.

## Deliberately not on the roadmap

- **Scoring, grading, or a compliance percentage.** The kit reports what held. A score
  invites the category error of treating it as certification.
- **A SaaS dashboard.** The kit is an asset, not a product. Hosting findings would make
  every security review harder for no gain.
- **Weakening an invariant so more ledgers pass.** The finding is the product.
