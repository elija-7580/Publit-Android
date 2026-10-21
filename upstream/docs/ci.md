# CI

The iOS job is the slow part: a GitHub macOS runner installs XcodeGen, builds the app and the tests from
scratch, boots a simulator and runs the UI tests against the live Transitous API (several minutes each).

## Faster workflow

[`ci-workflow.yml`](ci-workflow.yml) does the same checks with less waiting:

| Change | Saves |
| --- | --- |
| A newer push cancels the running one (`concurrency`) | the whole old run |
| Commits that only touch `*.md` and `docs/` do not run | the whole run |
| UI tests only with `[ui]` in the commit message (or `[ui F14 F15]` for single tests), on manual runs and nightly | most of the run |
| Homebrew without auto-update | 1–2 min |
| Simulator boots while the app builds | 1–2 min |
| No index store, one architecture, no dSYM | build time |
| SwiftPM cache for the Linux core tests | about 1 min |

Core tests (Linux), the build and the unit tests still run on every push.

GitHub does not let Publit's tooling change workflow files, so copy it once by hand:
GitHub → `.github/workflows/ci.yml` → ✏️ Edit → replace everything with the contents of `docs/ci-workflow.yml` → Commit.
Manual runs: Actions → CI → Run workflow (field "UI tests": `all`, `none` or `F14,F15`).

## Even faster

- Run the tests on a Mac: `xcodegen generate`, then ⌘U in Xcode (seconds for unit tests once built).
- Larger GitHub runners (`macos-15-xlarge`, paid) build about twice as fast.
