# Releasing

A release is a tag. `v1.2.0` publishes `1.2.0`; nothing else triggers a publish.

The workflow is `.github/workflows/release.yml`. It runs `verify` first, builds a signed
bundle, uploads it to the Sonatype Central Publisher Portal, and stops. **The final publish
is a button in the Portal, not a step in the pipeline** — a version on Central cannot be
deleted or replaced once it is out, so the irreversible action is a decision someone makes,
not a side effect of pushing a tag.

---

## One-time setup

None of this can be done from the repository. All four steps are needed before the first
release; the workflow fails with a specific message if any is missing.

### 1. Claim the namespace

At [central.sonatype.com](https://central.sonatype.com), register the namespace
`io.github.technomorphcorporation`. For an `io.github.*` namespace, verification is by proving
control of the GitHub organisation — the Portal asks you to create a public repository with a
generated name under that org, then checks for it.

This is why the group id is `io.github.technomorphcorporation` and not
`com.technomorphcorporation`: the latter would require control of
`technomorphcorporation.com`, which does not exist.

### 2. Generate a signing key

Central requires a GPG signature for every artifact, and verifies it against a public keyserver.

```bash
gpg --full-generate-key            # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>      # the value for the SIGNING_KEY secret
```

Publish the *public* key to a keyserver — Central looks it up there. Keep the private key
somewhere you will still have it in two years; losing it means every future release is signed
by a different key, which defeats the point of signing them.

Two things that fail in ways the error message does not explain:

- **Export the secret key, not the public one.** `--export-secret-keys`, not `--export`. The
  commands differ by one word and the resulting failure says only that a key ring could not be
  read. The workflow checks for this before it reaches Gradle.
- **`SIGNING_PASSWORD` must match the key.** If the key has no passphrase, leave the secret
  unset rather than inventing a value; a non-empty passphrase against an unprotected key fails
  at signing.
- **Set the key from a file, not from a shell variable.** Losing the line breaks is the most
  common way this fails, and it survives every obvious sanity check: the BEGIN and END markers
  are both still there, and the key is still unreadable. Gradle reports it as nothing more than
  `Could not read PGP secret key`.

  ```bash
  gpg --armor --export-secret-keys <KEY_ID> > /tmp/signing-key.asc
  gh secret set SIGNING_KEY < /tmp/signing-key.asc
  rm /tmp/signing-key.asc
  ```

  Pasting into the web UI preserves newlines too. The workflow reports the line count of the
  secret — a count, never the key — so this is visible in the log rather than inferred.

### 3. Generate Portal tokens

In the Portal, under your account, generate a user token. It comes as a username and a
password; neither is your login. These are the `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`
values below.

### 4. Add four repository secrets

Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `SIGNING_KEY` | the ASCII-armored **private** key from step 2, including the BEGIN/END lines |
| `SIGNING_PASSWORD` | the passphrase for that key — leave the secret unset if the key has none |
| `CENTRAL_USERNAME` | Portal token username from step 3 |
| `CENTRAL_PASSWORD` | Portal token password from step 3 |

---

## Cutting a release

`main` is protected: it takes no direct pushes, from anyone, and a merge needs the `verify`
check green. **Tags are not affected** — branch protection governs branches — so the release
itself is still two commands.

```bash
# 1. Rehearse against the commit you intend to tag. Builds and signs, checks the
#    bundle, uploads nothing.
gh workflow run release.yml -f version=1.0.0

# 2. Tag and push. This is a tag, not a branch, so it goes straight up.
git tag -a v1.0.0 -m "1.0.0"
git push origin v1.0.0

# 3. Watch the run, then publish from the Portal when it reports VALIDATED.
```

The dry run is worth doing the first time. It exercises signing, the bundle layout and the
completeness check without touching Central, which is the half of the process that cannot be
undone if it is wrong.

Tag the commit you rehearsed, not whatever `main` has drifted to since. The dry run proves a
specific tree builds and signs; a tag on a later commit is a different tree and an untested
one.

## After the release

Update the version in `docs/index.md`. The site shows a literal coordinate for people to copy,
so it is the one place a released version is written down by hand and nothing keeps it in step
— the same drift a wiki would have had, kept in the repository so at least a reviewer sees it.

Bump the development version in `gradle.properties`. This is a change to `main`, so it goes
through a pull request like any other:

```bash
git switch -c chore/bump-to-1.0.2-SNAPSHOT
# edit gradle.properties: version=1.0.2-SNAPSHOT
git commit -am "Bump the development version past 1.0.1"
git push -u origin chore/bump-to-1.0.2-SNAPSHOT
gh pr create --fill
gh pr merge --squash --auto      # lands itself when verify goes green
```

`--auto` queues the merge behind the check rather than making you watch a run. Nothing else
about the bump changes; it is the same one-line edit it always was.

The tag is what a release is built from, so this is not a value to keep in step with anything
— it is what local and CI builds produce *between* releases, and it has to sit above the last
release. Leaving it at `1.0.1-SNAPSHOT` after releasing 1.0.1 means a locally built jar sorts
below code it already contains, which is the sort of thing that costs an afternoon when
someone is bisecting a report.

Bump to the lowest plausible next version. Raise it to a MINOR when a feature actually lands:
a new invariant in a MINOR can turn a client's build red, so that belongs in a decision rather
than in a default.

Because the bump lands after the tag, `main` briefly carries a development version equal to
the release that was just cut. That window is harmless — nothing is built from `main` during
it — but it is why the bump is a step in this document rather than something to get to
eventually.

## A note on what CI covers

`verify` runs on every push and pull request and is the required check. The Postgres example
is **not** in it — it needs Docker and takes minutes, and a gate people wait for is a gate they
learn to route around. It runs in its own workflow on `main` after a merge, nightly, and on
demand.

The consequence, stated rather than buried: a pull request can merge and break the Postgres
example. It will go red on `main` within minutes, naming the commit. That is a deliberate
trade of a slower signal for a faster gate, and worth revisiting if it ever costs more than it
saves.

## What the bundle contains

Three modules, each with a jar, a sources jar, a javadoc jar and a POM, plus a `.asc`
signature and md5/sha1/sha256/sha512 checksums for every file. Gradle also writes
`maven-metadata.xml`, which the Portal rejects, so the bundle task excludes it. The workflow
asserts all of this before uploading rather than discovering it from a rejection.

## Local verification

```bash
./gradlew centralBundle -Pversion=1.0.0 \
  -PsigningKey="$(gpg --armor --export-secret-keys <KEY_ID>)" \
  -PsigningPassword=...

unzip -l build/distributions/central-bundle-1.0.0.zip
```

Building a non-SNAPSHOT bundle without a key fails at `signMavenPublication` rather than
producing unsigned artifacts. That is deliberate.

## What has been verified, and what has not

Verified locally: the bundle layout, that every module produces all four artifacts, that the
`maven-metadata.xml` exclusion removes all of them, that a release version refuses to build
unsigned, and that a SNAPSHOT version refuses to bundle at all.

**Not verified:** signing with a real key, the upload itself, and the Portal's validation
response. No release has ever been cut, and no artifact has ever been published. The first run
is the first test of those three, which is the reason it is staged rather than automatic.
