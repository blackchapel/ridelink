# Working on RideLink

## Branch / PR / merge workflow

- Never push new work directly to `main`. Create a feature branch for it.
- Push the feature branch and let the user run the manual **Test
  Release** workflow (`.github/workflows/build-apk.yml`,
  `workflow_dispatch` with a `branch` input) against it themselves.
- Only after the user has tested that build and explicitly approved it,
  open a PR from the feature branch into `main`.
- **Never merge a PR without the user's explicit go-ahead**, even after
  it's open and even if CI on it is green.
- Merging to `main` does **not** trigger a release by itself — multiple
  PRs merge into `main` over time, and the user separately, manually
  dispatches `.github/workflows/release.yml` (the signed **Production
  Release** build) whenever they've decided it's time to cut one.

## Docs: read before writing code, update after

Before starting any change, read all three of these -- they're the
fastest way to pick up real context (architecture, what already exists,
what's already known to be broken or risky) without re-deriving it from
source or repeating work already flagged as a gap:

- `README.md` — user/build-facing: what the app does, how to build it,
  how to test it.
- `docs/SYSTEM_STATE.md` — technical snapshot: architecture, module map,
  what's implemented, the release pipeline as it actually exists.
- `docs/KNOWN_GAPS.md` — open limitations/risks. Check this before
  assuming something is unhandled -- it may already be a known,
  deliberate trade-off rather than an oversight.

Then, any change that touches app behavior, the build, or the release
pipeline updates all three of these in the same branch/PR — don't let
them drift. That includes `docs/KNOWN_GAPS.md`: add newly-found gaps,
and remove/update ones this change actually resolves.

## Versioning: decide the semver bump yourself

`app/version.properties` holds only `MAJOR.MINOR` — PATCH is fully
automatic (the Production Release workflow computes it from existing
release tags every run; never edit it by hand). MAJOR/MINOR is not
automatic, and deciding it is your job as part of whichever PR changes
behavior, not the user's:

- **MINOR** bump: any backward-compatible addition or enhancement (a new
  feature, a new option, non-breaking behavior change).
- **MAJOR** bump: a breaking change (removes/renames something a user or
  the pipeline depended on, changes behavior in an incompatible way).
- **No bump**: a pure fix, refactor, docs-only, or CI/process change with
  no behavior addition.

Only bump if the current `MAJOR.MINOR` has already been released (a
`v<MAJOR.MINOR>.*` tag exists). If `version.properties` is already ahead of
the last release -- an earlier merged PR bumped it and nothing has shipped
since -- that bump covers this PR too; bumping again just skips a version
number.

Apply the bump to `version.properties` as part of the same PR that earns
it — don't ask the user to classify it, and don't leave it for release
time (release time only ever adds the automatic patch number on top of
whatever MAJOR.MINOR is currently checked in).

## Changelog and release notes

The release notes are **hand-written in `CHANGELOG.md`**, not derived from PR
titles (GitHub's auto-notes only list merged PRs, which says little when a
PR is big).

- Every PR that adds, changes or fixes something a user would notice adds
  a line under `## Unreleased` in `CHANGELOG.md`, in plain words for the
  people using the app (groups: Added, Changed, Fixed; "Project" for things
  around the app such as the build or repository). Docs-only and internal
  refactors can skip it.
- **Cutting a release:** before the user runs **Production Release**, open a
  small PR that renames `## Unreleased` to `## <version> (<date>)` and starts
  a fresh empty `## Unreleased` above it. `<version>` is
  `app/version.properties` MAJOR.MINOR plus the next patch (one more than the
  highest `v<MAJOR.MINOR>.*` tag, or 0). The workflow publishes that section
  as the notes and fails before building if it is missing.
- GitHub's generated "What's Changed" PR list is added below the notes.
  `.github/release.yml` groups it by PR label (see `docs/SYSTEM_STATE.md`),
  so when opening a PR still apply whichever of `feature`/`enhancement`,
  `fix`/`bug`, `documentation`/`docs`, or `chore`/`maintenance`/
  `dependencies`/`ci` actually describes it — an unlabeled PR just falls into
  "Other Changes", which is a worse release note, not a hard failure.

## Sandbox constraint

This environment usually cannot compile Kotlin/Android locally (no local
Gradle/Android SDK access) — GitHub Actions is the only real compiler
check. Review diffs carefully before pushing; a failed CI round-trip
costs several minutes each time.
