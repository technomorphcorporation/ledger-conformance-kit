# RFC 0006 — One-to-many matching: when one record equals several

| | |
|---|---|
| **Status** | Draft |
| **Author** | Manjul Bhakri |
| **Created** | 2026-10-08 |
| **Affects** | `lck` (reconciliation engine), `Mapping` |
| **Depends on** | RFC 0003 (reconciliation engine) |

---

## Summary

Extend reconciliation beyond one-to-one key matching. Let a `Mapping` declare **matching rules**
that pair one record on one side with a group of records on the other — a settlement batch against
its transactions, a net movement against gross amounts and fees, a split payment against its parts —
deterministically, explainably, and without ever guessing.

## Motivation

RFC 0003 matches records by business key. Real money rarely lines up that neatly. A bank statement
shows one deposit for a processor's settlement of four hundred card transactions, net of fees. A
payout is split across sellers, the platform and tax. A refund reverses part of a charge two days
later. In every one of these, no single key on one side equals a single key on the other, so a
one-to-one engine reports every record as `MISSING` or `EXTRA` — which is accurate and useless.

This is the hardest part of real reconciliation, and the part most teams still do by hand in
spreadsheets. It deserves its own design rather than a flag on RFC 0003.

## Non-goals

- **No probabilistic or learned matching.** Every match is produced by a declared rule, and the
  report names the rule. A reconciliation an auditor cannot replay is not evidence.
- **No silent tolerance.** Amount or date tolerances exist only when declared, with a justification,
  as in RFC 0003.
- **No unbounded search.** Finding which subset of records sums to a target is computationally
  intractable in general. The engine never attempts it without a declared grouping key and a hard
  limit, and it reports when the limit was reached.

---

## Design

### 1. Matching rules

A `Mapping` may declare an ordered list of rules. Each rule has a shape, a grouping, and an
equation.

| Shape | Example | Grouping | Equation |
|---|---|---|---|
| **One-to-one** | Ledger entry ↔ bank line | Business key | Fields equal (RFC 0003) |
| **One-to-many** | Bank deposit ↔ settlement batch | Declared batch or reference id | `A.amount = Σ B.amount` |
| **Many-to-many** | Several transfers ↔ several statement lines | Declared reference id | `Σ A.amount = Σ B.amount` |
| **Net with fees** | Net deposit ↔ gross charges and fees | Declared batch id | `A.net = Σ B.gross − Σ B.fee` (equation as declared) |
| **Windowed** | Late or early postings | Reference id, within a declared date window | As above, with window stated |

Rules are tried **in the declared order**. A record consumed by a rule is not available to later
rules. Every match records the rule that produced it.

### 2. The grouping key is mandatory

A rule must group by a declared field — a batch id, settlement reference, payout id. Matching on
amount and date alone is permitted only as an explicitly declared last-resort rule, inside a
declared window, and **every match it produces is reported as `WEAK`**, never as matched.

### 3. Ambiguity is a result, not something to resolve

If more than one candidate group satisfies a rule — two batches with the same net amount on the
same day — the engine does not pick. It reports `AMBIGUOUS`, lists every candidate, and leaves the
records unmatched. Choosing would manufacture a reconciliation that looks clean and may be wrong.

### 4. Bounded search

Within a declared group, when the rule needs a subset rather than the whole group — "which of these
transactions make up this partial settlement?" — the engine searches only up to a declared group
size and a declared iteration limit. Hitting either limit is reported as `SEARCH_LIMIT`, with the
group named, so the gap is visible rather than silently skipped.

### 5. New break classes

Added to RFC 0003's taxonomy:

| Class | Meaning | Default severity |
|---|---|---|
| `UNMATCHED` | No rule matched the record | BLOCKER |
| `PARTIAL` | A group matched by key, but the equation does not balance — the residual is reported in minor units | BLOCKER |
| `AMBIGUOUS` | More than one candidate satisfied a rule | MAJOR |
| `WEAK` | Matched only by an amount-and-date rule, without a grouping key | MAJOR |
| `SEARCH_LIMIT` | The bounded search stopped before resolving a group | MAJOR |

### 6. The checks

| Ref | Check | Severity |
|---|---|---|
| REC-09 | Every record on both sides is matched by a declared rule | BLOCKER |
| REC-10 | Every matched group balances under its declared equation | BLOCKER |
| REC-11 | No record is matched ambiguously | MAJOR |
| REC-12 | No match relies on amount and date alone | MAJOR |

### 7. What a finding says

```
REC-10   FAIL   BLOCKER  Every matched group balances under its equation
         rule=net-with-fees   group=SETTLE-2026-09-14-03
         A.net = 41,820.55   Σ B.gross − Σ B.fee = 41,832.05   residual USD 11.50
         B records: 418   fees: 418   — one fee line likely missing or duplicated
```

The residual is always stated. A group that "nearly" balances is not matched.

---

## Verification

**No false positives.** Each must reconcile clean:
- **C-07** a settlement batch whose transactions sum exactly to the deposit
- **C-08** a net deposit equal to gross charges minus fees, under the declared equation
- **C-09** a payout split across seller, platform and tax that sums exactly
- **C-10** a posting one day late, inside the declared window

**No false negatives.** Each must trip exactly its named checks:

| Fixture | Injected discrepancy | Must trip |
|---|---|---|
| D-12 | One transaction missing from a batch | REC-10 (`PARTIAL`, residual = that amount) |
| D-13 | One fee line duplicated | REC-10 (`PARTIAL`, residual = that fee) |
| D-14 | Two batches with identical net amounts on the same day, no batch id | REC-11 (`AMBIGUOUS`) — and nothing matched |
| D-15 | A deposit matched only by amount and date | REC-12 (`WEAK`) |
| D-16 | A posting outside the declared window | REC-09 (`UNMATCHED`) |
| D-17 | A group larger than the declared search limit | `SEARCH_LIMIT` reported, group named |

D-14 is the one that matters most: an engine that matched either batch there would produce a clean
report that may be wrong.

---

## Compatibility

Additive to RFC 0003. A `Mapping` with no matching rules behaves exactly as RFC 0003 one-to-one
matching. Works in every execution tier of RFC 0003 §7, though one-to-many rules run on fetched
records after narrowing, not inside fingerprints.

## Open questions

1. **Rule authoring.** Should rules be declared in Java only, or also in a small configuration
   format so finance and operations teams can read and review them?
2. **Partial settlements across scopes.** How should a group that spans the edge of a scope — some
   transactions on 30 September, the deposit on 1 October — be reported?
3. **Currency conversion.** Should a rule ever match across currencies with a declared rate, or is
   that always out of scope, given the kit's rule that money is never summed across currencies?
