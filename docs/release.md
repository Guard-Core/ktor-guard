# Release runbook (ktor-guard)

Adapter release choreography. The shared mechanics (Portal namespace, user
token, GPG, publishing limits, rollback semantics) are documented once in the
engine repo: [guard-core-jvm docs/release.md](https://github.com/Guard-Core/guard-core-jvm/blob/master/docs/release.md).
This file covers what differs here.

## Versioning law

- Artifact `com.guard-core:ktor-guard`; version `1.0.0` (fresh family
  version). The version lives in `build.gradle.kts`; the tag must equal it.
- The engine lockstep pin lives in `gradle/libs.versions.toml` (`guardCore =
  "4.3.3"`) and in the workflow env (`GUARD_CORE_JVM_REF`); they move only
  when the engine train moves.
- Tags are bare `vX.Y.z` only (`v1.0.0`). The `tag-consistency` job audits
  this (the guard-core-rs invisible-rule incident on `refs/tags/v4.3.2` is
  the standing lesson).
- groupId `com.guard-core`; Kotlin packages stay `com.guardcore.*`.

## Version-flip checklist (the release PR)

The workflow enforces this: tagging without the flip fails `tag-consistency`
with an explicit message.

- [ ] `build.gradle.kts`: `version = "1.0.0-SNAPSHOT"` -> `version = "1.0.0"`.
- [ ] `gradle/libs.versions.toml`: `guardCore` still reads the lockstep engine
      version (`4.3.3`).
- [ ] The `GUARD_CORE_JVM_REF` pin (ci.yml env + settings comments) already
      points at the released engine SHA (bumped at train time, per the
      family spec).
- [ ] `CHANGELOG.md`: the unreleased section renamed to
      `## [1.0.0] - YYYY-MM-DD` with the engine pin stated.
- [ ] CI green on the release PR; merge it; tag the merge commit.

## Release choreography

Hard order: the engine artifacts MUST be live on Maven Central first. The
`pre-flight` job checks both `com.guard-core:guard-core-engine` and
`com.guard-core:guard-core` at the pinned lockstep version on repo1.maven.org
and refuses to run anything if they are not (engine-before-adapters, enforced
mechanically, not on the honor system).

1. Complete the engine train first: guard-core-jvm tagged, released, Portal
   deployments released, repo1 answering 200 for both engine artifacts
   (engine docs/release.md, steps 1 and 5).
2. Merge the version-flip PR.
3. Tag: `git tag v1.0.0 && git push origin v1.0.0`. Gates run in order:
   pre-flight (engine live) -> tests at tag (composite ON, same as ci.yml) ->
   tag-consistency (bare tag equals the in-file version).
4. Create the GitHub release for `v1.0.0`. The publish job:
   - verifies the four secrets are set (Portal user token + GPG key);
   - checks repo1 for this exact artifact and skips if already live
     (changed-only rerun safety);
   - builds the staging bundle with `GUARD_CORE_USE_COMPOSITE=false`, so the
     engine dependency resolves from the repositories (mavenLocal, then Maven
     Central) - the published artifact graph, not the composite checkout;
   - uploads the bundle to the Central Portal Publisher API
     (`publishingType=USER_MANAGED`) and polls until VALIDATED.
5. Release the deployment in the Portal UI (central.sonatype.com, Publishing
   -> Deployments).
6. Verify liveness:
   `curl -f https://repo1.maven.org/maven2/com/guard-core/ktor-guard/1.0.0/ktor-guard-1.0.0.pom`

## Post-release audits (per train)

1. Release page for `v1.0.0` exists with the expected notes (per-tag audit;
   the php v4.3.1 lesson).
2. repo1 answers 200 for the artifact (the same check the workflow uses).
3. No deployment left VALIDATED/PENDING in the Portal.
4. The `GUARD_CORE_JVM_REF` pin (ci.yml env + settings comments) moves to
   the released engine SHA AT TRAIN TIME, before the consumer tags: the
   adapter's at-tag tests must run against the engine that actually ships
   (the family spec pins this; a pin bump is an ordinary development PR).

## Rerun safety, failure recovery, rollback

Same rules as the engine runbook: workflow_dispatch re-drives skip anything
already live on repo1; a VALIDATED deployment you do not want gets Dropped in
the Portal; published artifacts are immutable - no rollback after the Portal
Release click, only a fix version.
