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
7. **Update the counts.** Several documents state how many invariants there are, and a stale
   count on the front page is the first thing a reader disbelieves. Find the candidates with:

   ```bash
   grep -rniE "fourteen|fifteen|sixteen|all 1[0-9]" \
     --include='*.md' --include='*.yml' --include='*.java' --include='*.kts' . | grep -v /build/
   ```

   Then use judgement, because not every hit should change:

   - **Stale** — the README headline and table, its "holds all *n*" line, the JUnit test count
     (an `adapter TCK` check plus the invariants), `docs/index.md`, `docs/adapter-guide.md`,
     `CLAUDE.md`, `ROADMAP.md`, and the class javadoc on `Invariants`.
   - **History, and must not change** — the changelog entry for a past release, an RFC saying
     how many invariants existed when it was written, a comment describing a bug as it was
     seen. Rewriting these makes them wrong.
   - **Not about invariants at all** — the shard count in `ReferenceLedger` and the pool size
     in the Postgres example both mention sixteen.

   Two live outside that search and have to be done by hand. **`docs/_config.yml`** sets the
   published site's title and subtitle, and is configuration rather than prose, so a search for
   sentences will not find it — it has gone stale once already. And the **GitHub repository
   description**, which is in settings and not in the tree at all.

   Where a count is in code rather than prose, refer to the registry instead of writing a
   number. A comment saying "every invariant in the registry" cannot go stale.
8. **Add a changelog entry under `[Unreleased]`, and say it can turn a build red.** A new
   invariant is a MINOR that fails ledgers which were passing yesterday. Point at `--baseline`.

## Style

- Comments explain **why**. A comment that restates the code should be deleted.
- Money is `long` subunits. A `double` in a money position is a bug even if tests pass.
- No new dependencies in `lck-spi` or `lck`.
  `complianceCheck` will fail you.
- Findings are read by people who think in currency: use `Invariants.money()`.

## Before you open a PR

```bash
./gradlew verify        # build, tests, mutation check, compliance gates
./gradlew demo          # reference holds all, naive breaks ten — a fixture, not a regression
./gradlew dockerTest    # the Postgres example. Needs Docker; skips cleanly without it
```

`dockerTest` is not part of `verify`, because it needs Docker and takes minutes. It runs on
`main` and nightly. If you touch the invariants or the Postgres example, run it before opening
the pull request — otherwise you will find out after your change has merged.

## Licence

Apache-2.0. By contributing you agree your contribution is licensed under it.
