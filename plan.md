# Jenkins to GitHub Actions Migration Plan

## Objective
Migrate Jenkins Groovy jobs in this repository to GitHub Actions workflows in `../webapp/.github/workflows` while preserving behavior, security boundaries, and operational controls, using the decisions captured in `research.md`.

## Ground Rules (from research decisions)
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

## Phase 1: Inventory and Mapping
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
3. Produce a final scope list for this migration batch and confirm skipped jobs are excluded.

## Phase 2: Workflow Scaffolding Standards
1. Create/standardize reusable templates in `../webapp/.github/workflows` for:
- CI/test reusable jobs.
- Scheduled automation jobs.
- Deploy component jobs (determine/build/deploy/verify/rollback primitives).
2. Define required baseline blocks for all workflows:
- `permissions` least privilege.
- `timeout-minutes`.
- `concurrency` naming convention.
- Runner selection (`runs-on: [ephemeral-runner]`).
- Shared setup action usage (`../webapp/.github/actions/setup/action.yml`).
3. Define standard notification/report pattern:
- Terminal report job with `if: always()`.
- Slack success/failure notifications with consistent payload fields.

## Phase 3: Security and Secret Migration
1. Replace Jenkins secret-fetch shell patterns with GitHub-native auth:
- OIDC + WIF via `google-github-actions/auth` for GCP access.
- Repository/environment secrets only when non-cloud credentials are unavoidable.
2. Set workflow/job `permissions` explicitly per workflow class:
- Add `id-token: write` only where required.
- Preserve `pull_request` safety boundary (no `pull_request_target` for untrusted code).
3. Port network restrictions:
- Reuse secure-network allowlist pattern from existing workflows.
- Document any incremental domain exceptions required by migrated jobs.

## Phase 4: Implement by Migration Group

### Group A: PR/CI checks (highest priority)
1. Compare Jenkins `webapp-test`/coverage behavior with existing `webapp-test.yml`.
2. Fill parity gaps:
- Shard/fanout behavior.
- Artifact upload and failure diagnostics.
- Merge queue triggers (`merge_group`) where applicable.
3. Migrate remaining CI check jobs to reusable workflows and wire entry workflows.
4. Validate branch protection required checks align with new workflow names.

### Group B: Scheduled maintenance/content automation
1. Migrate jobs one by one with exact UTC cron parity:
- `webapp-maintenance`
- `update-ownership-data`
- `update-devserver-static-images`
- `update-i18n-lite-videos`
- `build-current-sqlite`
- `find-failing-taskqueue-tasks`
2. For writeback jobs, standardize on `KHAN_ACTIONS_BOT_TOKEN`.
3. Preserve existing repo clone dependencies for now (with TODO comments for future reduction).
4. Add per-workflow runbook notes in YAML comments for operational ownership.

### Group C: Deploy and rollback primitives (highest risk)
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
5. Keep manual guardrails for high-risk operations (`fastly`, rollback, traffic/version operations).

### Group D: Specialized/legacy
1. Skip `make-allcheck.groovy`.
2. Migrate `qa-metrics.groovy` with minimal behavior change.
3. Add ownership/TODO comment indicating target future migration to `qa-tools` repo.
4. Review remaining low-frequency jobs and mark explicit disposition in migration matrix.

## Phase 5: Validation and Cutover
1. For each migrated workflow, run validation checklist before merge:
- Trigger parity (manual/schedule/PR/push/merge_group).
- Input parity (types/defaults/required/validation behavior).
- Side-effect parity (deploy/upload/push/status updates).
- Timeout/retry/concurrency parity.
- Notification parity.
2. Execute dry-runs or non-prod runs where possible, then merge.
3. Cut over immediately per job after merge:
- Disable Jenkins job.
- Record cutover timestamp and workflow URL in tracking doc.
4. Monitor first successful/failed run and resolve parity issues before next batch.

## Phase 6: Documentation and Operational Handoff
1. Add/update runbooks in `../webapp` for:
- Manual dispatch procedures.
- Rollback instructions.
- Failure triage and Slack alert expectations.
2. Add CODEOWNERS/owner metadata for each migrated workflow.
3. Create a migration completion checklist by job family.
4. Publish remaining technical-debt backlog:
- Buildmaster API replacement.
- Cross-repo dependency reduction.
- Runner specialization (if needed beyond `ephemeral-runner`).

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
