# The suite against the pattern everyone copies

There is a schema that circulates as *how to build a double-entry ledger in PostgreSQL*. It is in
blog posts, in gists, in answers, and — because it is short and looks right — in production. This
is what happens when the fifteen invariants are pointed at it.

**The subject is a pattern, not a project.** Nobody is named as defective. The finding is about
what a schema teaches and what it leaves out, which is not any one author's fault and is worth
more than any one author's bug.

## The expectation was wrong, and that is the useful part

The run was set up expecting the concurrency failures: lost updates on a hot account, a balance
diverging from its journal, the read-modify-write class that our own
[article](https://www.linkedin.com/pulse/your-ledger-can-9999-available-100-incorrect-manjul-bhakri-v9gdf)
is about.

**None of them happened.** The taught pattern holds every one of those invariants, and it holds
them for a reason worth stating plainly: **it does not store balances.** The balance is a `SUM`
over the entries. There is no row to read, modify and write back, so the entire lost-update class
has nowhere to occur. On the thing most likely to lose money under load, the tutorials are right.

What fails is not concurrency. It is **validation** — and it fails completely.

## What was run

| | |
|---|---|
| Subject | [`TutorialLedger`](../lck/src/test/java/com/technomorph/lck/examples/postgres/TutorialLedger.java) — the common denominator of the published schemas |
| Database | `postgres:16-alpine`, READ COMMITTED, one transaction per posting |
| Seeds | `42` and `8823714`, identical verdicts |

Reproduce with `./gradlew dockerTest --tests '*TutorialPattern*'`.

Every design choice is recorded in that class's javadoc with where it comes from. The most-linked
instance of the pattern is a gist that has been copied for a decade
([`gist.github.com/NYKevin/9433376`](https://gist.github.com/NYKevin/9433376)), which keeps
balances in a materialised view over the entries — the same derived-balance idea with a refresh
step.

## Result

**Eight held. Four broke. Three were not applicable.** Same on both seeds.

| | Invariant | Result |
|---|---|---|
| ✅ | INV-02 journal is append-only | pass |
| ✅ | INV-06 no lost updates on a hot account | pass — 500 of 500 |
| ✅ | INV-07 value conserved under concurrent transfers | pass — 400 of 400 |
| ✅ | INV-08 balance equals the journal projection | pass — *see the note* |
| ✅ | INV-10 no precision drift over many small movements | pass — 1000 of 1000 |
| ✅ | INV-12 reversal is compensation, never deletion | pass |
| ✅ | INV-13 state rebuilds from the log | pass — *see the note* |
| ✅ | INV-14 per-account ordering is monotonic | pass |
| ❌ | **INV-04 duplicate submission is a no-op** | **fail** |
| ❌ | **INV-05 concurrent duplicates collapse to one** | **fail** |
| ❌ | **INV-01 every transaction is balanced per currency** | **fail** |
| ❌ | **INV-11 currencies cannot be mixed in one transaction** | **fail** |
| — | INV-03 hash chain | not applicable |
| — | INV-09 overdraft guard | not applicable |
| — | INV-15 duplicate of a refused transaction | not applicable |

### The one that costs money

```
INV-05  64 of 64 submissions of one idempotency key applied, expected at most 1
        — the key was claimed 64 times, and the customer is charged 704.00
```

Eleven pounds, charged sixty-four times. Not a race that needs unlucky timing: the schema has
**no idempotency concept at all**, so every retry is simply a new payment. A mobile client on a
flaky connection does this to itself.

INV-04 is the same defect without concurrency — one replay, two extra journal entries.

### The two that create money

INV-01 submits a transaction debiting 24.00 and crediting 23.00. It was applied, and a pound
appeared. INV-11 closes a USD debit with a EUR credit, and that was applied too.

**Read these two carefully, because they are narrower than they look.** Nothing in the schema
stops either, but application code plainly could. The honest finding is not "the tutorials tell
you to create money" — it is that **the schema is not a backstop**, so correctness rests entirely
on every call site being right, forever, including the one written at 2am in eighteen months. The
sources show neither the constraint nor the validation.

## The remedy, which was run rather than asserted

The same pattern with **two schema changes and no application changes** holds all twelve
applicable invariants:
[`ConstrainedTutorialLedger`](../lck/src/test/java/com/technomorph/lck/examples/postgres/ConstrainedTutorialLedger.java).

**1. A unique index, so an idempotency key means something.**

```sql
CREATE TABLE applied_transactions (idempotency_key TEXT PRIMARY KEY, ...);
-- in the same transaction as the entries:
INSERT INTO applied_transactions (idempotency_key, transaction_id) VALUES (?, ?)
ON CONFLICT (idempotency_key) DO NOTHING;      -- zero rows == someone else has it
```

The constraint is the guarantee. A `SELECT` first and an `INSERT` after is the version that loses
the race and the version that passes review.

**2. A deferred constraint trigger, so a transaction has to balance.**

```sql
CREATE CONSTRAINT TRIGGER entries_balance
  AFTER INSERT ON entries DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_transaction_balances();
```

`DEFERRABLE INITIALLY DEFERRED` is the whole subtlety. The legs are inserted one at a time, so the
sums only balance at the end. A plain row-level trigger fires after the first insert and rejects
every transaction there has ever been. And grouping the sum **per currency** is what makes one
trigger cover INV-11 as well: a transaction netting to zero across two currencies nets to zero in
neither.

That is the entire difference. Same tables, same derived balance, same isolation level, same
application code — four failures to none.

## Three rows that mean less than they appear to

**INV-08 and INV-13 cannot fail here.** Both compare the balance against the journal projection,
and for this pattern the balance *is* the journal projection — the same `SUM` asked twice. They
are true and uninformative, and a reader should not take them as evidence the read path was
checked against anything. A ledger with a stored balance is where those two rows start carrying
information.

**INV-15 is not applicable, and the reason is interesting.** It catches a ledger whose idempotency
record has two states instead of three — one that tells a concurrent caller a payment already
succeeded when the transaction is about to be refused. You cannot have that defect without having
idempotency first. The subtle bug is unreachable from here; the crude one, INV-05, is what is
actually present.

**INV-09 is not applicable because there is no overdraft guard.** The schema constrains an amount
to be positive and nothing else. Not declaring a capability the ledger does not have is deliberate:
failing a ledger for lacking a feature it never claimed would be a finding about the invariant.

## What this run does not show

- **Nothing about performance, which is the actual trade.** A derived balance is why the
  concurrency invariants pass, and it costs a scan of every entry for that account on every read.
  That cost is exactly why teams move to a stored balance — and a stored balance is where the
  lost-update class appears. **The taught pattern is correct until you optimise it, and the
  optimisation is what introduces the bug.** The suite measures correctness and has nothing to say
  about the scan.
- **One schema, one workload, one Postgres version.** Fifteen properties on `postgres:16-alpine`.
- **No fault injection.** Nothing kills a process or partitions a network.
- **Not a survey.** This is the common denominator of what circulates, implemented once and
  measured. It is not a claim about any particular post, and no post was run.

## The implementations that already do this

The careful ones add exactly the two things found missing.
[`pgledger`](https://github.com/rykroon/pgledger) deduplicates on a client-supplied transfer id
with `ON CONFLICT (id) DO NOTHING` and enforces balance rules at the account level. That it exists,
and that the fix is two DDL statements, is the evidence these are known problems with known
solutions rather than unavoidable ones.

Which is the point of publishing this at all. The pattern is not wrong about accounting. It is a
schema that teaches a structure and stops before the constraints, and a schema without constraints
is a suggestion.

---

*Run under [the research disclosure practice](research-disclosure.md). No project is the subject
and no maintainer was contacted, because there is no finding about anyone's software here — the
subject is a pattern, implemented in this repository, and the code is in the tree above.*
