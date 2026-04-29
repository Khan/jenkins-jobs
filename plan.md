# Jenkins to GitHub Actions Migration Plan

## Objective
Migrate Jenkins Groovy jobs in this repository to GitHub Actions workflows in `../webapp/.github/workflows` while preserving behavior, security boundaries, and operational controls, using the decisions captured in `research.md`.

## Migration Standards (apply to every migrated workflow)
- Use modular reusable workflows (`workflow_call`) for deploy components; external orchestrator will call these.
- Use `workflow_dispatch` with explicit inputs for operator-driven flows.
- Use `ephemeral-runner` for all migrated jobs initially; annotate original Jenkins label in comments.
- Keep Buildmaster API integration for now; add TODO comments for future GitHub-native migration.
- Use GitHub `concurrency` for singleton/lock replacement.
- Use direct Slack notification steps (existing webapp/frontend patterns), not alertlib.
- Keep multi-checkout behavior where currently required.
- Preserve cron cadence exactly (translated to UTC cron).
- Cut over each job immediately after merge (no dual-run default).
- Skip migration of `make-allcheck.groovy`.
- Migrate `qa-metrics.groovy`, with a TODO note to move ownership to `qa-tools` repo later.

## Phase 1: Inventory and Scope Lock
1. Build a migration matrix for every `jobs/*.groovy` file:
- Job name and source file.
- Trigger type (manual/schedule/PR/push/chained).
- Inputs (string/boolean/choice, defaults, required status).
- Secrets/auth dependencies (Jenkins secrets, GCP usage, bot token).
- External systems touched (GCP, Buildmaster, Slack, GCS, Docker, other repos).
- Jenkins lock/singleton/timeout/retry behavior.
- Migration disposition: `migrate`, `skip`, or `migrate-later`.
2. Identify direct `build(job: ...)` chains and map each to:
- Reusable workflow call (`workflow_call`) or
- Explicit operator dispatch boundary (`workflow_dispatch`).
3. Produce and confirm final in-scope job list for this batch.

## Phase 2: Platform Scaffolding in `webapp`
1. Create/standardize reusable templates in `../webapp/.github/workflows` for:
- CI/test reusable jobs.
- Scheduled automation jobs.
- Deploy component jobs (determine/build/deploy/verify/rollback primitives).
2. Define baseline workflow skeleton elements:
- `permissions` least privilege.
- `timeout-minutes`.
- `concurrency` naming convention.
- Runner selection (`runs-on: [ephemeral-runner]`).
- Shared setup action usage (`../webapp/.github/actions/setup/action.yml`).
3. Define standard report/notification skeleton:
- Terminal report job with `if: always()`.
- Slack success/failure notification pattern.

## Phase 3: Migrate Group A (PR/CI)
1. Compare Jenkins `webapp-test`/coverage behavior with existing `webapp-test.yml`.
2. Fill parity gaps:
- Shard/fanout behavior.
- Artifact upload and failure diagnostics.
- Merge queue triggers (`merge_group`) where applicable.
3. Migrate remaining CI check jobs to reusable workflows and wire entry workflows.
4. Validate branch protection required checks align with new workflow names.
5. Cut over Group A jobs by disabling Jenkins equivalents after merge.

## Phase 4: Migrate Group B (Scheduled Automation)
1. Migrate jobs one by one with exact UTC cron parity:
- `webapp-maintenance`
- `update-ownership-data`
- `update-devserver-static-images`
- `update-i18n-lite-videos`
- `build-current-sqlite`
- `find-failing-taskqueue-tasks`
2. For writeback jobs, standardize on `KHAN_ACTIONS_BOT_TOKEN`.
3. Preserve existing repo clone dependencies for now (with TODO comments for future reduction).
4. Cut over each job immediately after successful merge.

## Phase 5: Migrate Group C (Deploy/Rollback Primitives)
1. Implement reusable deploy component workflows owned by `webapp`:
- Determine target services/versions.
- Build artifact/image.
- Deploy execution.
- Post-deploy verification.
- Rollback primitive.
2. Add strict serialization:
- `concurrency.group` by deploy surface/environment.
- `cancel-in-progress: false` for deploy flows.
3. Replace Jenkins approvals with operator-dispatch boundaries:
- Build and deploy steps separated by explicit dispatch inputs.
4. Retain Buildmaster signaling with TODO comments for future replacement.
5. Cut over deploy-related Jenkins jobs after parity checks and successful staged runs.

## Phase 6: Migrate Group D and Closeout
1. Migrate `demo-district-update-pass.groovy` with current behavior parity, then document follow-up cleanup opportunities separately.
2. Skip `make-allcheck.groovy`.
3. Migrate `qa-metrics.groovy` with minimal behavior change and TODO ownership note.
4. Review remaining low-frequency jobs and mark explicit final disposition in migration matrix.
5. Disable all migrated Jenkins jobs and verify no orphaned required jobs remain.

## Phase 7: Validation, Documentation, and Handoff
1. Run parity checklist for every migrated job:
- Trigger parity.
- Input parity.
- Side-effect parity.
- Timeout/retry/concurrency parity.
- Notification parity.
2. Publish runbooks in `../webapp` for manual dispatch, rollback, and failure triage.
3. Assign ownership/oncall metadata for migrated workflows.
4. Publish follow-up backlog:
- Buildmaster API replacement.
- Cross-repo dependency reduction.
- Runner specialization if `ephemeral-runner` is insufficient.

## Security/Auth Gate (checked in each migration PR)
For every migrated workflow before merge:
1. Remove Jenkins-style ad hoc secret-fetch shell patterns.
2. Add OIDC + WIF via `google-github-actions/auth` where GCP access is required.
3. Use GitHub Secrets/Environments only for non-GCP credentials that cannot use WIF.
4. Keep `pull_request` security boundaries (no `pull_request_target` for untrusted code).
5. Set explicit least-privilege `permissions` and only add `id-token: write` where needed.

## Deliverables
- `plan.md` (this file).
- Migration matrix document (job-by-job source of truth).
- New/updated workflows in `../webapp/.github/workflows`.
- Cutover log (Jenkins-disabled jobs mapped to workflow runs).

## Exit Criteria
- All in-scope Jenkins jobs are either migrated with validated parity or explicitly skipped with rationale.
- Jenkins jobs in migrated scope are disabled.
- Oncall owners have documented runbooks and notification coverage.
- No open P0/P1 parity gaps remain for deploy, test, or scheduled automation behavior.
