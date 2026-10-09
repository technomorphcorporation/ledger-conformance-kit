# RFC 0004 — The evidence pack: a run you can hand to someone else

| | |
|---|---|
| **Status** | Draft |
| **Author** | Manjul Bhakri |
| **Created** | 2026-10-08 |
| **Affects** | `lck` reports |
| **Depends on** | — (consumes a differential run's output, RFC 0003, when present) |

---

## Summary

Every run — single-system or differential — can emit an **evidence pack**: one
directory containing a human-readable report, a machine-readable result, the run's full context, an
explicit statement of what was *not* tested, and a manifest of hashes so anyone can confirm nothing
was altered after the run.

## Motivation

A finding is only useful if the person receiving it can trust it without being in the room. Today
a team that runs the kit has a report. What they cannot easily do is hand that report to a bank
partner, an auditor or their own board, and have the recipient know exactly which version of the
kit ran, against what, with which capabilities declared, what was skipped, and that the file is the
one the run produced.

That is a packaging problem, not a testing one. It is worth solving once, in the kit, so every run
produces evidence in the same shape.

## Non-goals

- **Not a certification.** The pack records what ran. It does not attest that a system is correct,
  and it must not be represented as doing so. The existing rule stands: no score, no grade, no
  percentage.
- **No signing inside the kit.** Cryptographic signing needs key management and, typically, a
  dependency. The pack is designed to be signed *outside* the kit, by whoever stands behind it.
- **No network.** The pack references no remote asset, enforced by the existing `complianceCheck`.

---

## Design

### 1. Layout

```
lck-evidence-<run-id>/
  report.md          human-readable
  report.html        human-readable, no remote assets
  results.json       machine-readable, versioned schema
  context.json       everything needed to reproduce the run
  not-tested.md      what was out of scope, skipped, or not applicable — and why
  MANIFEST.sha256    SHA-256 of every file above
```

### 2. Context — what a recipient needs to know

`context.json` records, at minimum:
- kit version and the git commit it was built from
- adapter names and versions, and the capabilities each declared
- adapter TCK result for each adapter
- seed, run mode, start and end time
- an operator-supplied environment label — never inferred, so a pack cannot accidentally claim to
  describe production
- for a differential run: both adapter names, and which was baseline and which candidate, with
  justification

### 3. `not-tested.md` — the section that makes the rest credible

Generated, not hand-written. It always lists:
- every invariant or check reported **not applicable**, with the capability it needed
- every check reported **unreachable**
- for a differential run, which checks were serial-only and therefore not asserted concurrently
- the standing limits of the kit: no performance, no security, no assessment of the accounting
  model, no process killed and no network partitioned unless a later RFC adds fault injection
- the seed caveat for any concurrency finding

A pack without `not-tested.md` is invalid, and the build that produces one fails.

### 4. Integrity

`MANIFEST.sha256` lists the SHA-256 of every file in the pack, computed with
`java.security.MessageDigest` — no dependency. `lck verify <pack>` recomputes and compares.

The pack is deliberately signable by standard tools outside the kit:

```
gpg --detach-sign MANIFEST.sha256
```

Whoever signs is making their own statement about the pack. The kit makes none.

### 5. Language rules for generated text

Generated text in the pack:
- says **held**, **broke**, **not applicable**, **unreachable** — never "passed certification",
  "compliant" or "verified correct"
- states money per currency in minor units, never summed across currencies
- names the environment label in the first lines of `report.md`

---

## Verification

- **E-01** A run with every check passing still produces a non-empty `not-tested.md`.
- **E-02** Altering any byte of any file in a pack makes `lck verify` fail, naming the file.
- **E-03** A pack produced with an invariant not applicable lists that invariant and its missing
  capability in `not-tested.md`.
- **E-04** No generated file contains the strings "certified", "compliant" or "verified correct" —
  asserted as a build check alongside `complianceCheck`.
- **E-05** `report.html` references no remote asset — the existing compliance rule, applied here.

---

## Compatibility

Additive. Existing reports are unchanged; the pack is opt-in via `--evidence <dir>` and an
equivalent JUnit configuration.

## Open questions

1. **Schema versioning.** `results.json` will be consumed by tools other than the kit. Should its
   schema be published and versioned independently of the kit's own version?
2. **Redaction.** Findings name transaction ids and accounts from a scratch environment. Should the
   pack support an operator-declared redaction pass for packs shared outside the team — and if so,
   how is the manifest kept honest about what was redacted?
3. **Retention.** Should the kit recommend anything about how long packs are kept, or is that
   entirely the operator's policy?
