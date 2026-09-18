# Disclosure practice for unsolicited research

This governs findings from running the kit against software nobody asked us to test.

**It is not the [engagement protocol](publication-protocol.md).** That one covers paid work, and
says so in its third line. The two differ in almost every mechanic, because the situation is
different: there is no client, no contract, no fee, and nobody who agreed to be tested. What that
changes is the direction the obligations run, not how many there are.

## The one asymmetry everything follows from

**They did not ask for this.** A maintainer publishing a ledger under an open licence owes us
nothing, has not agreed to a process, and did not choose to be examined. That makes the bar for
care higher than in paid work, not lower — a paying client has a contract, a named counterparty
and a remediation window they negotiated. A maintainer has whatever we decide to give them.

The second asymmetry cuts the other way and has to be stated plainly rather than smuggled in. In
paid work the client's identity is **always confidential**. Here the subject is public software,
naming it is unavoidable, and naming it is the point: a report about "an open-source ledger"
teaches nobody anything and cannot be checked. So the protections below are not the engagement
protocol's protections — they are different ones, chosen for a case where anonymity is neither
possible nor desirable.

## Before anything is run

- **Against our own instance, never theirs.** A cluster or database we stand up, on our own
  machine or in our own CI, from a published artifact. We do not create accounts on hosted
  services and run a write-heavy conformance suite against someone's shared infrastructure.
  `AdapterTck` TCK-00 refuses a non-empty environment before any check writes, which is the
  structural version of this promise rather than a stated intention.
- **The licence gets read first.** Source-available and proprietary licences can restrict
  benchmarking or publication. Where a licence restricts it, we do not publish.
- **A version and a digest recorded.** A finding that cannot say exactly which build produced it
  is not reproducible, and not reproducible means not a finding.

## What happens when something fails

**First question: is it ours?** Every failure is treated as a defect in the kit or the adapter
until that is ruled out. This is not politeness; it is the base rate. Across the two runs
published so far, **every defect found was ours** — in the adapters, in the harness, and in tests
that turned out to assert nothing. A conformance suite that reported its own bugs as the ledger's
would be worse than no suite. (No count here on purpose: a number in prose goes stale, and the
run records carry the current list.)

Ruling it out means, at minimum: the adapter passes the TCK; the invariant passes a known-correct
ledger; the failure reproduces on a second seed; and the mutant for that invariant still trips it.

**Then, if it survives all that:**

1. **Private report to the maintainers first.** Through whatever channel they name for security or
   for bugs — a `SECURITY.md` contact, a private advisory, a maintainers' address. Not a public
   issue, not a tweet, not a mention in a talk.
2. **Ninety days before publication, and longer on request.** Granted by default the first time
   and not rationed. If a fix is genuinely in progress the window stays open; the window exists to
   give people time, not to create a deadline we can point at.
3. **A shorter window if they ask for one.** Unlike a paying client, an open-source maintainer may
   want the finding public immediately — it helps their users and often their own triage. Their
   call, not ours.
4. **The draft, thirty days before publication.** They may correct factual errors, which is
   binding; and attach a response of any length, published unedited alongside the report.
5. **No veto.** This is the sharpest difference from the paid protocol, and it is deliberate. A
   client who commissions a report has bought a veto over individual findings. Nobody has bought
   anything here, so there is nothing to have bought a veto with — and a finding suppressible by
   the party it concerns is not independent research. Factual corrections are binding; conclusions
   are not negotiable.

## What gets published

- The invariant, the failure class, and the numbers — in currency subunits, as the kit reports
  them
- A minimal reproduction: the version, the digest, the seed, and the adapter, so anyone can re-run it
- The maintainers' response, if they gave one, unedited
- The fix, if one shipped, and credit to whoever shipped it

## What does not

- **Anything we did not verify.** Speculation about what else might be wrong, severity inflation,
  or extrapolation from one invariant to a system's general quality
- **A grade, a score, or a certification.** The kit reports which invariants held on the date it
  ran. Turning that into a league table would be the most commercially tempting thing available
  and the fastest way to deserve being ignored
- **Comparisons framed as rankings.** Two ledgers with different data models are not more and less
  correct than each other; they make different things expressible. Both published runs contain
  rows that are *not applicable* precisely because of this, and a ranking would erase exactly the
  information that matters
- **A finding whose subject we could not reach.** If a project has no working contact, we do not
  publish by default — we say so and keep trying

## Clean runs

A clean run is published immediately, with no window and no notice, because there is nothing to
disclose. Both runs so far have been clean, and they are the more useful half of the corpus: they
are what makes a failing report credible when one eventually appears.

A clean run is evidence about that run. It is not an endorsement, not a certification, and does not
transfer to another version.

## If the finding is in us

Published as ours, in the changelog and in the run record, naming what was wrong. The two run
records in `docs/` do exactly that: `docs/formance.md` and `docs/tigerbeetle.md` both carry dated
corrections stating that an earlier version of the page overstated what the ledger had
demonstrated. That is the same standard applied inward, and it is the only thing that makes the
standard worth anything applied outward.

## Scope

A conformance run tests the properties in the suite, on one build, under one workload, on the date
it ran. It is not an audit, not a security review, not a benchmark, and not a statement that a
system is correct in any broader sense. Every report says so in those words.

*This document describes intended practice, published so that it can be held to. It creates no
obligation on anyone else and confers no rights over the kit, which is Apache-2.0: run it, fork it,
and publish whatever you find without asking anyone.*
