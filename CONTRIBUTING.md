# Contributing

## The one rule that matters

**A new invariant ships with a mutant.** An invariant with no seeded defect that only it
catches is not pulling its weight, and it will not be merged.

Two failure modes govern everything here, and the second is worse:

- A **false negative** means a client ships with a clean scorecard and loses money anyway.
- A **false positive** fails a correct ledger. Their engineers find it, they are right,
  and the credibility of every other finding goes with it.

`MutationTest` asserts both on every build.

## Adding an invariant

1. Write the **production symptom** first — the incident an engineer would recognize from
   their own on-call history. If it is just a restatement of the title, reconsider.
2. Open an RFC in `rfcs/` describing the failure, the proposed check, and the mutant.
3. Implement in `Invariants.REGISTRY`. Every failure path returns a number.
4. Add the mutant to `Mutants.all()`.
5. `./gradlew verify` must be green.
6. Gate behind a `Capability` if it needs a feature not every ledger has.

## Style

- Comments explain **why**. A comment that restates the code should be deleted.
- Money is `long` subunits. A `double` in a money position is a bug even if tests pass.
- No new dependencies in `lck-spi` or `lck`.
  `complianceCheck` will fail you.
- Findings are read by people who think in currency: use `Invariants.money()`.

## Before you open a PR

```bash
./gradlew verify        # build, tests, mutation check, compliance gates
./gradlew demo          # reference holds 14, naive breaks 8 — a fixture, not a regression
```

## Licence

Apache-2.0. By contributing you agree your contribution is licensed under it.
