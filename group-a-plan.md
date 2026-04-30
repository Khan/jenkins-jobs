# Group A Migration Plan: webapp-test (Bridge Phase)

## Context

Group A covers `webapp-test`, which runs unit tests, linters, and typechecks for the
`webapp` repo.

The PR trigger is **already migrated**: `webapp/.github/workflows/webapp-test.yml` runs on
`pull_request` events and fully replaces Jenkins for that case (sharded workers, timing-data
tracking on `ci/test-times`, PR comment with results).

What remains for the bridge phase is the **deploy-time invocation**: Buildmaster calls the
Jenkins `webapp-test` job with specific `GIT_REVISION`/`BASE_REVISION` params during the
deploy pipeline. The bridge makes Jenkins a thin wrapper that dispatches the GitHub Actions
workflow and waits for results rather than doing the work itself.

---

## What's Already Done

- Sharded test execution (replaces Jenkins' runtests-server/client architecture).
- `shard-tests` job computes changed files, generates matrix, passes via artifact.
- Per-language dep caching (Go, pnpm, Python venv, Gradle).
- Timing data stored and updated on `ci/test-times` branch.
- `report` job collects JUnit XML, posts PR comment with results.
- `concurrency.group` cancels superseded PR runs.

---

## What's Required for the Bridge Phase

### 1. Add `workflow_dispatch` trigger to `webapp-test.yml`

The existing workflow only fires on `pull_request`. Jenkins needs a `workflow_dispatch`
entry point with inputs that mirror the Jenkins params relevant to deploy-time testing:

| Jenkins param | GH Actions input | Notes |
|---|---|---|
| `GIT_REVISION` | `git_revision` | Commit/branch to test |
| `BASE_REVISION` | `base_revision` | Diff base; empty = run all tests |
| `SLACK_CHANNEL` | `slack_channel` | Default `#1s-and-0s-deploys` |
| `SLACK_THREAD` | `slack_thread` | Slack thread_ts for threading replies |
| `DEPLOYER_USERNAME` | `deployer_username` | For Slack pings |
| `REVISION_DESCRIPTION` | `revision_description` | Human-readable label for Slack |
| `BUILDMASTER_DEPLOY_ID` | `buildmaster_deploy_id` | Pass-through; not used by tests |

Jenkins-only params not needed in GH Actions:
- `CLEAN`: GH runners are always clean.
- `NUM_WORKER_MACHINES` / `CLIENTS_PER_WORKER`: replaced by dynamic sharding.
- `JOB_PRIORITY`: Jenkins queue concept; no equivalent needed.

Existing PR jobs use `github.event.pull_request.head.sha` and
`github.event.pull_request.base.sha` for checkout and diff. With `workflow_dispatch` these
must come from inputs instead. The affected jobs are `build-runtests`, `shard-tests`,
`run-tests`, `update-test-times`, and `report`.

### 2. Port the independent Slack test-summary notification

`analyzeResults()` in the Jenkins job calls `testing/testresults_util.py summarize-to-slack`
**independently of `notify()`** — this sends a structured per-test breakdown to the Slack
channel/thread. Because this message is not sent by `notify()`, Jenkins will not
replicate it in bridge mode. It must be implemented in the `report` job of the GH Actions
workflow.

The equivalent step should:
- Run only when triggered by `workflow_dispatch` (PR runs already post a PR comment).
- Post to `inputs.slack_channel` (and thread to `inputs.slack_thread` if set).
- Include pass/fail counts, job URL, and deployer mention.
- Use `slackapi/slack-github-action` (consistent with our existing GH workflows).

`testresults_util.py` can still be run from within the workflow to generate the message body
if the output format is worth preserving exactly; alternatively, a simpler shell/python
script reading the JUnit XML suffices.

### 3. Parameterize checkout and diff in existing jobs

Every job that currently hard-codes `github.event.pull_request.*` refs needs a helper
expression:

```yaml
# resolved ref: PR head sha on pull_request, explicit input on workflow_dispatch
${{ github.event_name == 'workflow_dispatch' && inputs.git_revision || github.event.pull_request.head.sha }}
```

Same pattern for `BASE_SHA` in `shard-tests`:

```yaml
${{ github.event_name == 'workflow_dispatch' && inputs.base_revision || github.event.pull_request.base.sha }}
```

### 4. Jenkins thin wrapper (in `webapp-test.groovy`)

Replace the body of the Jenkins job with a dispatch-and-wait call:

```groovy
onMaster('5h') {
    notify([slack: [channel: params.SLACK_CHANNEL, ...],
            github: [sha: params.GIT_REVISION, ...],
            buildmaster: [sha: params.GIT_REVISION, what: 'webapp-test']]) {
        def runId = kaGit.dispatchGithubActionsWorkflow(
            repo: "Khan/webapp",
            workflow: "webapp-test.yml",
            ref: params.GIT_REVISION,
            inputs: [
                git_revision: params.GIT_REVISION,
                base_revision: params.BASE_REVISION,
                slack_channel: params.SLACK_CHANNEL,
                slack_thread: params.SLACK_THREAD,
                deployer_username: params.DEPLOYER_USERNAME,
                revision_description: REVISION_DESCRIPTION,
                buildmaster_deploy_id: params.BUILDMASTER_DEPLOY_ID,
            ]
        )
        kaGit.waitForGithubActionsWorkflow(runId)
    }
}
```

`dispatchGithubActionsWorkflow` / `waitForGithubActionsWorkflow` are new shared utilities
that need to be added to `vars/kaGit.groovy` (or a new `vars/ghActions.groovy`). They wrap
the GitHub API (`POST /repos/{owner}/{repo}/actions/workflows/{id}/dispatches` +
polling `GET /repos/.../actions/runs?event=workflow_dispatch`).

---

## Key Decisions

### Decision 1: Extend existing `webapp-test.yml` vs create a new workflow

**Option A (chosen): Add `workflow_dispatch` to the existing workflow.**

- Pros: Single workflow, no duplication of job logic. Inputs and conditional refs are the
  only additions. Easy to keep PR and deploy-time behavior in sync.
- Cons: Conditional expressions (`github.event_name == 'workflow_dispatch' && ... || ...`)
  scatter through the file.

**Option B: New `webapp-test-deploy.yml` that calls the existing workflow via `workflow_call`.**

- Pros: Clean separation; existing workflow untouched.
- Cons: `workflow_call` + `workflow_dispatch` both exist means double-wrapping; harder to
  share artifacts across workflows; adds nav complexity.

### Decision 2: Slack test-summary format

**Option A (chosen): Run `testresults_util.py summarize-to-slack` from within the `report`
job, piping output to `slackapi/slack-github-action`.**

- Pros: Exact message parity with the Jenkins job; no rewrite of message logic.
- Cons: Keeps alertlib/python dependency in the workflow; `testresults_util.py` must be
  accessible in the checkout (it is — it's in `webapp`).

**Option B: Write a new lightweight script or inline shell in the workflow.**

- Pros: Removes alertlib dependency.
- Cons: Message diverges from current format; requires reimplementing summarization logic.

The `report` job already runs `testing/testresults_util.py`-adjacent parsing (the
`[gha-no-creds]` skips extraction). Running the summarize step there is natural.

### Decision 3: Jenkins dispatch-and-wait mechanism

**Option A (chosen): Poll the GitHub API from Jenkins using the `gh` CLI via `kaGit`.**

- Pros: Minimal new infrastructure. `gh run watch` blocks until the workflow completes
  and exits non-zero on failure — exactly what Jenkins needs.
- Cons: Requires `gh` to be installed and authed on Jenkins master. Run-ID retrieval
  from a dispatch is slightly awkward (must poll for new runs matching the sha/ref).

**Option B: Use a GitHub Actions webhook / status-check callback to Jenkins.**

- Pros: No polling.
- Cons: Requires Jenkins to expose an inbound endpoint; more infrastructure.

### Decision 4: GitHub commit status ownership in bridge mode

Jenkins `notify()` already posts a GitHub commit status for the Jenkins job. The GH
Actions workflow will also post its own native statuses per-job. During bridge:

- **Keep both.** The Jenkins status (`webapp-test` context) is what Buildmaster watches.
  GH Actions statuses are supplementary and visible in the PR UI.
- This means there will be two sets of status checks during bridge. That is acceptable
  and expected.

---

## Open Questions / Resolved Decisions

### Q1 (resolved): Slack summary generation

**Answer:** Extract a new `testing/testresults_summarize.py` that prints the summary to
stdout when run as `__main__`. `testresults_util.py` imports from it so there is no
duplication. The `report` job pipes its stdout to `slackapi/slack-github-action`.
This avoids pulling in alertlib or GCP secrets into the GH Actions environment.

### Q2 (resolved): SLACK_THREAD format

**Answer:** Confirmed. `slackapi/slack-github-action`'s `thread_ts` field accepts the
float timestamp string Buildmaster already passes.

### Q3 (resolved): SHA as `ref` for dispatch

**Answer:** If `GIT_REVISION` is a bare SHA (40-char hex), the bridge path is skipped
and the Jenkins job falls back to native test execution. The branch-vs-SHA check is
Jenkins-side logic gating the bridge (see Phase D). This avoids the need for SHA→ref
resolution and keeps the fallback path safe.

### Q4 (monitor): "No tests to run" in deploy context

**Answer:** `notify()` should treat a skipped GH Actions workflow as success; Buildmaster
should receive a correct status. We'll verify this during Phase E testing.

### Q5 (monitor): `update-test-times` without a PR context

**Answer:** The worktree-based push to `ci/test-times` should work with a bare SHA
dispatch since it does its own `git fetch`. Will confirm during Phase E testing.

---

## Implementation Steps

### Phase A: Extend `webapp-test.yml` for `workflow_dispatch`

1. Add `workflow_dispatch:` trigger with the inputs listed in the table above.

2. Define a top-level env var or reusable expression to resolve the active SHA and base SHA:
   ```yaml
   env:
     ACTIVE_SHA: ${{ github.event_name == 'workflow_dispatch' && inputs.git_revision || github.event.pull_request.head.sha }}
     BASE_SHA: ${{ github.event_name == 'workflow_dispatch' && inputs.base_revision || github.event.pull_request.base.sha }}
   ```
   Replace all hardcoded `github.event.pull_request.head.sha` and
   `github.event.pull_request.base.sha` references with these.

3. In `shard-tests`, use `env.BASE_SHA` in place of the `env.BASE_SHA: ${{ github.event.pull_request.base.sha }}` job-level env assignment.

4. Update `update-test-times` to use `env.ACTIVE_SHA` for its checkout ref.

5. Update `report` to use `env.ACTIVE_SHA` for its checkout ref; the PR comment step
   should be gated on `github.event_name == 'pull_request'` (it already uses
   `github.event.pull_request.number` so it will no-op on dispatch, but make it
   explicit).

### Phase B: Add Slack test-summary notification to `report` job

6. Extract `testing/testresults_summarize.py` from `testresults_util.py`: a standalone
   module that reads JUnit XML and prints a Slack-formatted summary to stdout. Update
   `testresults_util.py` to import and delegate to it so existing behavior is unchanged.

7. In the `report` job, add a step (after JUnit collection) gated on
   `github.event_name == 'workflow_dispatch'`:
   - Run `testing/testresults_summarize.py test-results/` and capture stdout as the
     message body.
   - Use `slackapi/slack-github-action` to post to `inputs.slack_channel` with
     `thread_ts: ${{ inputs.slack_thread }}` (omit the field when the input is empty).
   - Append the run URL: `${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}`.
   - Confirm the Slack token secret name against what's used in other `webapp` workflows.

### Phase C: Add dispatch/wait utilities to Jenkins vars

8. Add `dispatchGithubActionsWorkflow(...)` and `waitForGithubActionsWorkflow(runId)` to
   `vars/kaGit.groovy` (or a new `vars/ghActions.groovy`). Implementation:
   - `dispatch`: POST to GitHub API, then poll `GET /runs?event=workflow_dispatch&head_sha={sha}`
     for up to 30 s to retrieve the new run ID.
   - `wait`: `gh run watch {runId} --exit-status` (blocks until done, non-zero on failure).
   - No SHA→ref resolution needed: callers are responsible for not calling dispatch with a
     bare SHA (see Phase D gating logic below).

### Phase D: Add bridge flag to `webapp-test.groovy`

The Jenkins job must support both the existing native behavior and the bridge path,
switchable via a boolean parameter so the cutover can be tested and rolled back without a
code change.

9. Add a `USE_GITHUB_BRIDGE` boolean param (default `false`):

   ```groovy
   ).addBooleanParam(
      "USE_GITHUB_BRIDGE",
      "If true, dispatch tests to GitHub Actions instead of running them here.",
      false
   ```

10. Add a helper to detect bare SHAs:

    ```groovy
    def _isSha(String rev) {
        return rev ==~ /[0-9a-f]{40}/
    }
    ```

11. Replace the `try { stage("Running tests")... } finally { stage("Analyzing results")... }`
    block with a conditional:

    ```groovy
    if (params.USE_GITHUB_BRIDGE && !_isSha(params.GIT_REVISION)) {
        def runId = ghActions.dispatchGithubActionsWorkflow(
            repo: "Khan/webapp",
            workflow: "webapp-test.yml",
            ref: params.GIT_REVISION,
            inputs: [
                git_revision:          params.GIT_REVISION,
                base_revision:         params.BASE_REVISION,
                slack_channel:         params.SLACK_CHANNEL,
                slack_thread:          params.SLACK_THREAD,
                deployer_username:     params.DEPLOYER_USERNAME,
                revision_description:  REVISION_DESCRIPTION,
                buildmaster_deploy_id: params.BUILDMASTER_DEPLOY_ID,
            ]
        )
        ghActions.waitForGithubActionsWorkflow(runId)
    } else {
        // Jenkins-native path (unchanged)
        try {
            stage("Running tests") { runTests(); }
        } finally {
            if (!skipTestAnalysis) {
                stage("Analyzing results") {
                    withVirtualenv.python3() { analyzeResults(); }
                }
            }
        }
    }
    ```

    The `notify()` wrapper, `initializeGlobals()`, and all params remain unchanged. The
    outer `onWorker` call also stays, since the master node is needed to orchestrate even
    in bridge mode.

12. Keep `SLACK_CHANNEL`, `SLACK_THREAD`, `DEPLOYER_USERNAME`, `REVISION_DESCRIPTION`,
    `BUILDMASTER_DEPLOY_ID` params for backward compatibility with Buildmaster call sites.
    Keep `CLEAN`, `NUM_WORKER_MACHINES`, `CLIENTS_PER_WORKER`, `JOB_PRIORITY` as no-ops
    (do not remove them during bridge — Buildmaster may still pass them).

### Phase E: Validation

13. Run a deploy-context test dispatch manually (`workflow_dispatch` via GitHub UI) with a
    real `git_revision` (branch name) and `base_revision` from a recent deploy. Verify:
    - Correct test subset is selected.
    - Slack summary message appears in the right channel/thread.
    - JUnit results are uploaded.
    - Timing data is pushed to `ci/test-times`.
    - "No tests to run" short-circuit (Q4) resolves cleanly if triggered.

14. Run a full Jenkins job invocation with `USE_GITHUB_BRIDGE=true` against a staging
    deploy. Verify Buildmaster receives correct status via the Jenkins `notify()` wrapper.

15. Verify the fallback: run the Jenkins job with `USE_GITHUB_BRIDGE=true` but pass a bare
    SHA as `GIT_REVISION`. Confirm it silently falls back to the Jenkins-native path.

16. Once the bridge path is stable, flip the default of `USE_GITHUB_BRIDGE` to `true`.
    The native path remains available as a rollback switch indefinitely.
