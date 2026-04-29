# Phase 3 Research: Migrate Group A (PR/CI Jobs)

This document captures the Phase 3 requirements, system architecture, and open questions for migrating Group A CI jobs. See `research.md` for higher-level background and decisions.

## Scope

Phase 3 covers three Group A jobs:

| Source file | Disposition | Trigger |
|---|---|---|
| `webapp-test.groovy` | migrate-later | manual / chained (buildmaster) |
| `e2e-test.groovy` | migrate | manual / chained (buildmaster) |
| `go-codecoverage.groovy` | migrate | manual |

`webapp-test.groovy` is flagged `migrate-later` because its distributed test-server + multi-machine worker orchestration is architecturally complex. However, a partial GHA implementation (`webapp-test.yml`) already exists and covers the PR trigger path. Phase 3 therefore has two sub-scopes:

1. **Parity audit and gap-fill** for `webapp-test.yml` (compare against Jenkins behavior; fill gaps).
2. **New workflow migration** for `e2e-test` and `go-codecoverage`.

Phase 3 also covers:
- Adding `merge_group` trigger where applicable.
- Validating branch protection required check names align with GHA job names.
- Moving migrated jobs to `bridge` mode, then `github` mode after stability.

## Requirements

### Functional requirements

1. **`webapp-test` parity** — The existing `webapp-test.yml` must be at least as complete as Jenkins for all trigger paths:
   - PR trigger (already covered via `pull_request`).
   - Manual dispatch (`workflow_dispatch`) for operator re-runs and buildmaster-chained invocations.
   - Optional `merge_group` trigger for merge-queue gating (see open question 2).
   - Selective test running via `BASE_REVISION` diff semantics (currently covered by `github.event.pull_request.base.sha` for PRs; must generalize for dispatch).
   - Buildmaster status reporting (currently absent from `webapp-test.yml`; Jenkins job sends it).
   - Slack summarization via `testing/testresults_util.py summarize-to-slack` (Jenkins behavior; GHA version currently uses dorny/test-reporter + PR comment only).

2. **`e2e-test` migration** — The Jenkins job is already a thin dispatch wrapper:
   - Resolves a SHA1 and calls `tools/notify-workflow-status.ts` which dispatches an existing GHA workflow.
   - Migration removes the Jenkins intermediary and replaces it with a native GHA `workflow_dispatch` entry point.
   - Must preserve buildmaster integration (see open question 5) during bridge mode.
   - Must preserve Slack notification semantics (channel, thread, sender "Testing Turtle").
   - Must preserve `NUM_WORKER_MACHINES` as a passthrough to LambdaTest.

3. **`go-codecoverage` migration** — Straightforward single-job:
   - Clone webapp at a given SHA, install Go tools (`go-acc`, `gocover-cobertura`), run tests, generate `coverage.xml`.
   - Must replace Jenkins `publishCoverage` (cobertura adapter) with a GHA-compatible coverage report mechanism (see open question 3).
   - Must replace `jenkins-deploy-gcloud-service-account.json` with OIDC/WIF for GCP auth.
   - Must expose `GIT_REVISION` as a `workflow_dispatch` input.
   - Non-blocking test failures (`catchError(buildResult: 'SUCCESS')`) must be preserved.

4. **Bridge mode for all three jobs** — Each migrated job must go through `bridge` mode before Jenkins is disabled (per `research.md` cutover strategy). Bridge mode for chained jobs means Jenkins dispatches the GHA workflow, receives a result, and signals buildmaster; the GHA workflow contains the actual implementation.

5. **Branch protection alignment** — Required check names in branch protection must match the GHA job names produced by the migrated workflows. If the `webapp-test` workflow or job names change, branch protection must be updated atomically.

### Security and compliance requirements

1. Use `pull_request` (not `pull_request_target`) for PR-triggered CI — no untrusted code elevation.
2. Replace `jenkins-deploy-gcloud-service-account.json` with OIDC + WIF via `google-github-actions/auth` for `go-codecoverage` (only job in Group A that actually uses GCP).
3. `go-codecoverage` needs `id-token: write` for WIF; all other Group A workflows must not carry it unless needed.
4. `webapp-test.yml` must remain credential-free for PR-triggered runs (currently correct; must not regress when `workflow_dispatch` inputs are added).
5. `e2e-test` migration must keep the GHA workflow token (`jenkins_github_webapp_e2e_workflow_runner_token`) in GitHub Secrets, not re-fetched via `gcloud secrets versions access`.

### Reliability and operability requirements

1. `webapp-test.yml` already correctly uploads JUnit artifacts with `retention-days: 7` and uses `dorny/test-reporter`. Preserve this.
2. `go-codecoverage` must upload `coverage.xml` as a workflow artifact with a meaningful retention policy.
3. `e2e-test` must preserve the `5h` timeout from Jenkins (`onWorker('ka-test-ec2', '5h')`).
4. `go-codecoverage` must preserve the `5h` timeout and non-blocking failure semantics.
5. Slack notifications for `e2e-test` and `go-codecoverage` must use the Phase 2 `reusable-phase2-report.yml` pattern.

## Related System Architecture

### Current Jenkins control plane

**`webapp-test.groovy`** — Most complex of the three:
- Distributed test-server + N workers × M clients model.
- Test-server runs `runtests-server` on `ka-test-ec2`, discovers files to test/lint via `BASE_REVISION` diff (`deploy/trivial_diffs.py`, `testing/all_tests_for.py`, `testing/all_lint_for.py`).
- Worker machines poll the test-server for test assignments via HTTP on port 5001.
- Clients upload JUnit XML back to the test-server via `/end?filename=junit-N.xml`.
- Test-server stashes `test-info.db` to Jenkins master between runs for test time ordering.
- `analyzeResults()` calls `testing/testresults_util.py summarize-to-slack`.
- Buildmaster integration via `notify([buildmaster: [sha: ..., what: 'webapp-test']])`.
- GitHub commit status via `notify([github: [sha: ..., context: 'webapp-test', when: ...]])`.

**`e2e-test.groovy`** — Thin dispatch wrapper:
- Resolves `GIT_REVISION` → `GIT_SHA1` via `kaGit.resolveCommittish`.
- Calls `tools/notify-workflow-status.ts` (pnpm/tsx) in `webapp/testing/e2e` with the GitHub token and Slack token.
- That TypeScript tool dispatches an existing GHA e2e workflow (`Khan/webapp`) and polls for completion.
- Jenkins is doing nothing except: fetching secrets, cloning webapp, and running that script.
- Buildmaster integration via `notify([buildmaster: [sha: ..., what: E2E_RUN_TYPE]])` where `E2E_RUN_TYPE` is `"first-smoke-test"` or `"second-smoke-test"` based on whether the URL is production.
- IS_PRODUCTION logic: `E2E_URL == "https://www.khanacademy.org"`.

**`go-codecoverage.groovy`** — Single-node:
- Runs `go-acc ./services/... --ignore=testutil,generated,cmd` for coverage.
- Converts to cobertura via `gocover-cobertura`.
- Jenkins-specific: `publishCoverage adapters: [coberturaAdapter(path: 'coverage.xml')], sourceFileResolver: sourceFiles('STORE_LAST_BUILD')`.
- `STORE_LAST_BUILD` because `webapp` is ~1 GB.
- `catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE')` — individual test failures are non-blocking.
- No Slack notification in this job (no `notify()` call with Slack).

### Existing GHA implementations

**`webapp-test.yml`** (already merged into `webapp`):
- Triggers: `pull_request` (opened, synchronize, reopened) only — no `workflow_dispatch`, no `merge_group`.
- Four jobs: `build-runtests` → (`shard-tests` in parallel) → `run-tests` (matrix) → `report` + `update-test-times`.
- `shard-tests` replaces the Jenkins test-server: uses `runtests-server -batch-size-in-ms` to pre-assign test shards statically at startup instead of dynamically at runtime.
- `run-tests` is a matrix job (up to 50 workers). Each shard is independent; no HTTP test-server needed.
- Test timing DB stored on `ci/test-times` branch (replaces Jenkins stash of `test-info.db`).
- `report` job: dorny/test-reporter + PR comment with results link + skipped-test table.
- No `workflow_dispatch` inputs. No `SLACK_CHANNEL`/`SLACK_THREAD` parameter. No buildmaster calls.
- No `DEPLOYER_USERNAME` / `REVISION_DESCRIPTION` inputs.

**`e2e-setup.yml`** (already merged):
- `workflow_call` reusable. Takes `sha` input and `KHAN_ACTIONS_BOT_SSH_PRIVATE_KEY` secret.
- Sets up node, installs/caches `node_modules`, sets up Cypress with `mode: non-default`.
- Does not run tests itself; intended to be called by a parent e2e workflow.

**Phase 2 scaffolding** (already merged):
- `reusable-phase2-ci-test.yml`: parameterized CI job + report, `cancel-in-progress: true`.
- `reusable-phase2-report.yml`: writes step summary + optional Slack notification.
- `reusable-phase2-scheduled-job.yml`, deploy primitives: not directly relevant to Phase 3.

### Architectural differences between Jenkins and GHA test orchestration

| Concern | Jenkins (`webapp-test.groovy`) | GHA (`webapp-test.yml`) |
|---|---|---|
| Test distribution | Dynamic via runtests-server HTTP | Static sharding at `shard-tests` time |
| Timing data persistence | `test-info.db` stashed to Jenkins master | `ci/test-times` branch |
| Worker coordination | Workers poll server on port 5001 | Matrix jobs; each shard is independent |
| Test ordering | Server reorders by time using DB | Sharding uses timing DB to size shards |
| Failure diagnostics | JUnit XML uploaded to server, then slack | JUnit XML uploaded as artifacts, dorny/test-reporter |
| Buildmaster signaling | `notify([buildmaster: ...])` | Not present |
| Slack notification | `testresults_util.py summarize-to-slack` | dorny/test-reporter + PR comment |
| Manual / buildmaster dispatch | Jenkins job parameterization | Not yet present |

The GHA approach is architecturally different but achieves equivalent outcomes for the PR path. The gap is: manual/buildmaster dispatch, buildmaster signaling, and Slack summarization.

## Open Questions with Options and Pros/Cons

### 1) How to handle buildmaster-dispatched `webapp-test` invocations?

Jenkins `webapp-test.groovy` is invoked by buildmaster as part of the deploy chain (alongside `e2e-test`). The existing `webapp-test.yml` has no `workflow_dispatch` trigger or dispatch-oriented inputs.

**Option A**: Add `workflow_dispatch` with buildmaster-compatible inputs to `webapp-test.yml` directly.
- Inputs: `GIT_REVISION`, `BASE_REVISION`, `SLACK_CHANNEL`, `SLACK_THREAD`, `DEPLOYER_USERNAME`, `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID`, `JOB_PRIORITY`.
- Pros: Single workflow for PR and dispatch paths; simplest long-term; avoids a parallel file.
- Cons: `workflow_dispatch` on the same file that handles `pull_request` adds complexity to checkout logic (`github.event.pull_request.head.sha` vs explicit `GIT_REVISION`); the two trigger paths have meaningfully different parameter sets; must be careful not to introduce credential leakage if `workflow_dispatch` can be called without protection.

**Option B**: Create a separate thin `webapp-test-dispatch.yml` that accepts buildmaster inputs and calls `webapp-test.yml` via `workflow_call`.
- Pros: `webapp-test.yml` stays clean; dispatch path can have its own security boundaries; easier to review.
- Cons: Two files to maintain; `webapp-test.yml` must be refactored to be callable via `workflow_call`, which means adding `on.workflow_call` and possibly adjusting how trigger-specific context (e.g., `github.event.pull_request.base.sha`) is accessed.

**Recommendation**: Option B. The PR and dispatch paths are different enough (different SHA resolution, different output consumers, different trigger security contexts) that separating them reduces the risk of accidentally loosening PR security.

---

### 2) Should `webapp-test.yml` add a `merge_group` trigger?

GitHub merge queues emit `merge_group` events. If Khan Academy uses or plans to use GitHub's merge queue for `webapp`, the test workflow must respond to it.

**Option A**: Add `merge_group` now.
- Pros: Proactive; avoids a separate PR when merge queue is adopted.
- Cons: Adds a trigger that may not fire at all today; the merge-commit SHA handling differs slightly from PR SHA handling (could require testing).

**Option B**: Add `merge_group` only when merge queue is actually enabled.
- Pros: Keeps the diff minimal; easier to reason about.
- Cons: Will require a last-minute PR at adoption time; may block merge queue rollout.

**Recommendation**: Option B. Add a TODO comment in `webapp-test.yml` so it is not forgotten. Add `merge_group` in a dedicated follow-up once the merge queue decision is made.

---

### 3) How to replace Jenkins `publishCoverage` (cobertura) in `go-codecoverage`?

Jenkins has a first-class coverage-report plugin (`coberturaAdapter` + `STORE_LAST_BUILD`). GHA has no direct equivalent natively.

**Option A**: Upload `coverage.xml` as a workflow artifact; rely on developers downloading it manually.
- Pros: Zero external service dependency; simplest to implement.
- Cons: No UI trend line or diff annotation; removes the primary value of the Jenkins job.

**Option B**: Use [codecov.io](https://about.codecov.io/) or [coveralls.io](https://coveralls.io/) via their respective GHA actions.
- Pros: Hosted trend lines, PR annotations, badge support.
- Cons: External SaaS dependency; requires org account setup and token management; adds a new external service to trust.

**Option C**: Use `dorny/test-reporter` to render cobertura XML as a GitHub Check run (it supports cobertura format).
- Pros: Keeps everything in GitHub; no new external services; consistent with how `webapp-test.yml` already surfaces test results.
- Cons: Check run coverage view is less rich than codecov; no historical trend across runs.

**Option D**: Use GitHub's native code coverage feature (`actions/upload-artifact` + the `coverage` artifact naming convention supported by some GitHub beta features).
- Pros: Fully native if/when it becomes GA.
- Cons: Feature availability is uncertain and runner-version dependent; not stable enough to build on now.

**Recommendation**: Option C (dorny/test-reporter for the cobertura report). Upload the raw `coverage.xml` artifact as a fallback. Add a TODO for migrating to codecov if richer trending is needed.

---

### 4) How to preserve Slack test summarization (`testresults_util.py summarize-to-slack`) in `webapp-test`?

Jenkins runs `webapp/testing/testresults_util.py summarize-to-slack` to post a structured Slack message with per-test-type pass/fail counts, rerun commands, and a deployer ping. The GHA `webapp-test.yml` currently only posts a PR comment with a link to dorny/test-reporter.

**Option A**: Port `testresults_util.py summarize-to-slack` to a GHA step in the `report` job, using `slackapi/slack-github-action`.
- Pros: Full Slack message parity; keeps the rerun command and deployer ping.
- Cons: The Python script depends on `SLACK_TOKEN` and `DEPLOYER_USERNAME`, which are not present in PR-triggered runs; requires careful gating (only post to Slack for dispatch-triggered runs where a deployer is set).

**Option B**: Skip `summarize-to-slack` for PR-triggered runs (no deployer, no buildmaster context); add it only for dispatch/buildmaster-triggered runs via the dispatch wrapper (see question 1).
- Pros: Clean separation: PR CI is silent on Slack (as expected); deploy-triggered runs get the full summary.
- Cons: If the deploy workflow calls the dispatch wrapper, it gets Slack; standalone re-runs from the UI do not — which is probably acceptable.

**Option C**: Replace with a simpler Slack notification (just a pass/fail link) using `reusable-phase2-report.yml`.
- Pros: Consistent with other Phase 2 workflows; zero new scripting.
- Cons: Loses per-test-type breakdown, rerun commands, and deployer ping — meaningful regression for deploy operators.

**Recommendation**: Option B. Dispatch-triggered runs (via the wrapper in question 1) include Slack summarization via `testresults_util.py`. PR runs stay silent on Slack and communicate only via PR comments and GitHub Checks. This matches how developers experience both paths.

---

### 5) How to handle buildmaster integration during bridge mode for `e2e-test` and `webapp-test`?

Buildmaster currently relies on Jenkins `notify([buildmaster: ...])` calls to track test results. During bridge mode, Jenkins is the dispatch layer — it calls GHA and waits — so buildmaster signaling can still happen from Jenkins. But in `github` mode, the Jenkins job is disabled and buildmaster must learn of results some other way.

**Option A**: Add buildmaster HTTP calls directly to the GHA workflow (both `e2e-test` and `webapp-test`).
- Steps: `curl -X PATCH https://buildmaster-526011289882.us-central1.run.app/...` in a terminal job, using `BUILDMASTER_TOKEN` from GitHub Secrets.
- Pros: Correct behavior in `github` mode without Jenkins.
- Cons: Couples migrated workflows to buildmaster API; more work than bridge mode strictly needs; adds secrets to workflows that otherwise need none.

**Option B**: Keep buildmaster signaling in Jenkins (bridge mode only). In `github` mode, rely on GitHub commit status (`notify([github: ...])`) that Jenkins already sets — wait for buildmaster to switch to consuming GitHub commit statuses instead of its own API.
- Pros: Minimal GHA changes; leverages existing GitHub commit status integration; natural migration path as buildmaster evolves.
- Cons: Requires buildmaster to be capable of reading GitHub commit statuses (may require buildmaster changes); timing of that work is unknown.

**Option C**: Add buildmaster signaling only to the dispatch wrapper (question 1), not to `webapp-test.yml` itself.
- Pros: Keeps `webapp-test.yml` clean; the dispatch wrapper is already the boundary between Jenkins and GHA.
- Cons: In pure `github` mode the dispatch wrapper is also gone, so this only covers bridge mode.

**Recommendation**: Option A for `e2e-test` (since it is `migrate` status and will reach `github` mode in Phase 3). Add `BUILDMASTER_TOKEN` as a GitHub Secret and include buildmaster signaling steps in the terminal job. For `webapp-test` (`migrate-later`), use Option C (bridge-mode only) and add a TODO for the `github`-mode transition.

---

### 6) How to handle branch protection required check alignment?

If the GitHub Actions job names change (or new workflows replace old ones), existing branch protection "required status checks" referencing old check names will break or become stale.

**Option A**: Keep job names in migrated workflows identical to what they were in any existing GHA workflow (e.g., `webapp-test` → keep the job named `report` / `Test results` exactly as is).
- Pros: No branch protection changes needed.
- Cons: Job names that were chosen for Jenkins parity may not be ideal long-term; harder to rename later.

**Option B**: Update branch protection as part of the migration PR, atomically with the workflow rename.
- Pros: Clean naming; documented transition.
- Cons: Branch protection changes require admin access; a transient window where required checks are unset could allow unprotected merges.

**Option C**: Add new check names to branch protection before the migration PR merges (not remove old ones until the old job is confirmed gone).
- Pros: Zero-downtime transition; old and new checks coexist during bridge mode.
- Cons: More coordination; admin must update protection twice.

**Recommendation**: Option C. Document the required check names for each Group A job before migration begins. Perform two-phase branch protection updates (add new before removing old). This is the safest approach and maps directly to the `bridge` → `github` mode transition.

---

### 7) How exactly to wire `e2e-test` given it already dispatches a GHA workflow?

`e2e-test.groovy` today: Jenkins calls `tools/notify-workflow-status.ts`, which dispatches a GHA workflow in `Khan/webapp` and polls for completion. The "GHA workflow" is the real e2e runner; Jenkins is just a dispatch shim.

**Option A**: Create a new `e2e-test.yml` `workflow_dispatch` entry point in `webapp` that runs the same `tools/notify-workflow-status.ts` logic (or an equivalent shell step) natively in GHA.
- Pros: The GHA workflow is now the authoritative entry point; Jenkins is fully bypassed in `github` mode.
- Cons: The TypeScript dispatch script itself may rely on Jenkins-specific context (e.g., `BUILD_URL`); must audit and generalize those references.

**Option B**: Expand the existing e2e GHA workflow (whichever one `notify-workflow-status.ts` currently dispatches) to accept a `workflow_dispatch` with the Jenkins-compatible parameter set (`URL`, `GIT_REVISION`, `SLACK_CHANNEL`, `SLACK_THREAD`, `NUM_WORKER_MACHINES`, `DEPLOYER_USERNAME`, etc.).
- Pros: Removes the intermediate dispatch script entirely; simplest long-term architecture.
- Cons: Requires understanding which GHA workflow is currently being dispatched and whether it already has this interface; must trace `tools/notify-workflow-status.ts` to identify the target workflow.

**Option C**: Create a thin `e2e-test.yml` that accepts the Jenkins parameter set and calls the existing e2e reusable workflow via `workflow_call`.
- Pros: Clean separation of interface from implementation.
- Cons: Adds another YAML indirection layer.

**Recommendation**: Option B, if the target workflow already exists in `webapp`. Requires first tracing what `notify-workflow-status.ts` dispatches (and whether that workflow supports `workflow_dispatch` already). If the target workflow is not already parameterized, fall back to Option C as an intermediate step. This must be resolved before implementation begins.

---

### 8) What is the expected behavior difference for `webapp-test` when `BASE_REVISION` is empty?

Jenkins: if `BASE_REVISION` is empty, `files_to_test.txt` gets a literal `.` which means "run all tests." GHA `webapp-test.yml` currently always diffs against `github.event.pull_request.base.sha` and has no "run all tests" escape hatch.

**Option A**: Add a `run_all_tests` boolean input to the dispatch interface that bypasses the diff and passes `.` to `runtests-server`.
- Pros: Preserves Jenkins parity for the "full test suite" case (used by buildmaster for deploy smoke tests).
- Cons: Slightly more complex sharding logic.

**Option B**: Use an empty `GIT_BASE_SHA` input to signal "run all tests" in the dispatch path; shard-tests interprets empty base as full run.
- Pros: Cleaner interface; fewer booleans.
- Cons: Slightly less explicit; requires shard-tests to handle the empty-base case.

**Recommendation**: Option B. Consistent with the existing pattern (Jenkins empty string = all tests). Document clearly in the input description.

---

## Phase 3 Deliverable Checklist

- `phase-3-research.md` (this document).
- `webapp-test.yml` updated with `merge_group` TODO and parity gaps documented.
- `webapp-test-dispatch.yml` (new): `workflow_dispatch` entry for buildmaster/operator invocations.
- `e2e-test.yml` (new): `workflow_dispatch` entry replacing `e2e-test.groovy` Jenkins wrapper.
- `go-codecoverage.yml` (new): migrated coverage workflow with OIDC/WIF, dorny/test-reporter.
- Bridge-mode Jenkins wrappers for each job (Jenkins job updated to dispatch GHA and report result).
- Branch protection required check names updated per Option C (additive, then remove old).
- Cutover log entries for each Group A job (`bridge` → `github` transitions).

## Proposed Decisions to Confirm Before Implementation

1. Confirm dispatch wrapper approach for `webapp-test` (question 1 → Option B).
2. Confirm `merge_group` is deferred to a follow-up (question 2 → Option B).
3. Confirm `dorny/test-reporter` for `go-codecoverage` coverage rendering (question 3 → Option C).
4. Confirm Slack summarization is dispatch-path-only (question 4 → Option B).
5. Confirm buildmaster HTTP steps go in `e2e-test.yml` terminal job; `webapp-test` uses bridge-mode for now (question 5 → Option A for e2e-test, Option C for webapp-test).
6. Confirm two-phase branch protection update (question 6 → Option C).
7. Resolve which GHA workflow `notify-workflow-status.ts` dispatches before writing `e2e-test.yml` (question 7 → trace the script first).
8. Confirm `BASE_REVISION` empty = all tests in dispatch interface (question 8 → Option B).
