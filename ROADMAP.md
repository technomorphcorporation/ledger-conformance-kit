# Roadmap

Each version is a complete, useful thing. The rule is that nothing from a later version
gets smuggled into an earlier one — see `CLAUDE.md`.

## v1.0 — evidence (current)

Fifteen invariants, the adapter TCK, the mutation check, ratchet mode, four report
formats, the HTTP contract, build-enforced compliance gates. The claim it supports:
*your ledger held these properties under concurrency, retry and replay, on this date.*

CI runs `verify` on every push and pull request, and a tag builds a signed bundle for the
Sonatype Central Publisher Portal, staged for a manual publish — see `docs/releasing.md`.

**Remaining before tagging v1.0:**

- A worked Postgres adapter as a second known-correct implementation. The mutation corpus is
  currently vetted against one reference ledger, and a second would settle whether INV-08 and
  INV-13 are genuinely distinct or the same assertion at two scales
- `REVIEWERS.md`, and the `rfcs/` process exercised once for real
- An OpenAPI spec for the HTTP contract, so a client implements it from a specification rather
  than from prose
- The four one-time release prerequisites in `docs/releasing.md` — namespace claimed, signing
  key generated and published, Portal tokens issued, repository secrets set. Until those exist
  a tag builds a bundle and cannot upload it

## v1.1 — adoption

Reduce the only real friction, which is writing the adapter.

- Pre-built adapters for common ledger platforms
- Adapter generator: `lck new-adapter --kind jdbc|http|jvm`
- A reusable GitHub Action — `uses: technomorphcorporation/ledger-conformance-kit@v1` — so a
  consumer runs the suite without writing workflow steps, plus a GitLab CI template. Distinct
  from this repository's own CI, which already exists
- Soak mode: `--duration 30m --concurrency 512`, random seed, for the schedules a
  90-second PR run never reaches

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

- Mandate limits hold under concurrent agent spend
- A revoked mandate takes effect before the next commit, not eventually
- Agent-initiated reversals are compensation, never deletion
- Delegated authority is traceable from journal entry back to the human mandate

Deliberately unnumbered. Invariant numbers are allocated when an invariant ships, not
reserved — INV-15 was reserved here and has since been taken by the first one that was
actually written, which is the argument against reserving them.

These need a new `Capability`, which is a MINOR release — so it gets added when the invariants
that need it exist, not before. A constant was reserved in the SPI for exactly this and has
been removed: because adding is MINOR, reserving bought nothing, and a capability an adapter
could declare while no invariant exercised it was a promise with no test behind it.

## Deliberately not on the roadmap

- **Scoring, grading, or a compliance percentage.** The kit reports what held. A score
  invites the category error of treating it as certification.
- **A SaaS dashboard.** The kit is an asset, not a product. Hosting findings would make
  every security review harder for no gain.
- **Weakening an invariant so more ledgers pass.** The finding is the product.
