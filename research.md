# Jenkins Groovy -> Golang + GitHub Actions Migration Research

## Scope
Migrate the jobs in this repo from Jenkins Groovy pipelines (`jobs/*.groovy`, shared logic in `vars/*`, helper class in `src/org/khanacademy/Setup.groovy`) to:
- GitHub Actions workflows for orchestration and scheduling.
- Go executables for job business logic and reusable pipeline primitives.

This document captures requirements, current/target architecture, and open questions with options and pros/cons.

## Current System Architecture (As-Is)

### Execution Model
- Jenkins runs one Groovy file per job from `jobs/`.
- Shared pipeline behaviors are implemented in `vars/` (custom steps) and a Java/Groovy helper `Setup` class (`src/org/khanacademy/Setup.groovy`).
- Jobs run on either:
  - Jenkins master via `onMaster(...)`.
  - Labeled workers via `onWorker(label, timeout, ...)`, e.g. `ka-test-ec2`, `build-worker`, `znd-worker`, `ka-firstinqueue-ec2`.

### Control-Plane Features Used Heavily
- Parameterized runs (string/bool/choice parameters via `Setup.add*Param`).
- Cron schedules (`Setup.addCronSchedule(...)`).
- Timeouts (`withTimeout`).
- Parallel fanout (`parallel(...)` in multiple jobs).
- Manual approvals (`input(...)` in deploy-critical paths).
- Cross-job orchestration (`build(job: ...)` in `make-allcheck`, `emergency-rollback`, etc.).
- Build retention and concurrency controls (`resetNumBuildsToKeep`, `disableConcurrentBuilds` by default).
- Queue priority (`JOB_PRIORITY`) integrated with Jenkins Priority Sorter plugin.

### Shared Utility Layers
- `vars/kaGit.groovy`: safe checkout/fetch/merge/commit wrappers on `safe_git.sh`.
- `vars/notify.groovy`: Slack/email/GitHub/buildmaster notifications (through `alertlib/alert.py`).
- `vars/withSecrets.groovy`: fetch secrets from GCP Secret Manager and materialize scoped files/env.
- `vars/exec.groovy`: shell escaping + command wrappers.
- `vars/singleton.groovy`: Redis-backed "run once" gating.

### External Dependencies / Integrations
- GitHub repos (webapp/internal-services/buildmaster2/frontend/etc).
- GCP (App Engine, Cloud Run, GCS, Secret Manager, logging).
- Slack alerts and threaded messages.
- Buildmaster coordination (`BUILDMASTER_DEPLOY_ID`, status notifications).
- Fastly deploy flows.
- LambdaTest/Cypress E2E orchestration.
- Redis for singleton behavior.

## Job Inventory and Migration Complexity

### Tier 1: High-complexity deploy orchestration
- `build-webapp.groovy`
- `deploy-webapp.groovy`
- `deploy-znd.groovy`
- `webapp-test.groovy`
- `e2e-test.groovy`
- `determine-webapp-services.groovy`
- `merge-branches.groovy`
- `emergency-rollback.groovy`

Traits: complex parameters, parallelism, manual gates, buildmaster hooks, rich Slack messaging, multi-repo coordination.

### Tier 2: Medium complexity build/deploy jobs
- `deploy-fastly.groovy`
- `build-and-deploy-publish-worker.groovy`
- `build-publish-image.groovy`
- `update-translation-pipeline.groovy`
- `build-current-sqlite.groovy`
- `update-devserver-static-images.groovy`
- `update-ownership-data.groovy`
- `test-buildmaster.groovy`
- `deploy-buildmaster.groovy`
- `deploy-alert-context.groovy`

Traits: fewer orchestration edges, but still secrets, repo sync, and external system mutations.

### Tier 3: Low complexity / scheduled maintenance
- `find-failing-taskqueue-tasks.groovy`
- `notify-znd-owners.groovy`
- `go-codecoverage.groovy`
- `update-i18n-lite-videos.groovy`
- `webapp-maintenance.groovy`
- `qa-metrics.groovy`
- `make-allcheck.groovy`
- `firstinqueue-priming.groovy`
- `demo-district-update-pass.groovy` (currently flagged broken)

Traits: primarily scheduled or wrapper-style; good early migration candidates.

## Target Architecture (To-Be)

### High-Level
- GitHub Actions workflow per job class (or per job for one-to-one parity initially).
- Reusable Go module (suggested repo path: `internal/jenkinsjobs` or separate `go-runner` repo) encapsulating shared behaviors now in `vars/*`.
- Workflows call Go CLIs with typed flags instead of Groovy params.

### Proposed Layers
1. Workflow layer (`.github/workflows/*.yml`)
- Triggering (`workflow_dispatch`, `schedule`, `workflow_call`, `push` as needed).
- Concurrency groups and cancellation policy.
- Environment protection rules for approval gates.
- Permissions and OIDC identity setup.

2. Orchestration layer (Go)
- Typed config parsing from env/flags.
- Step orchestration, retries, timeouts, fan-out/fan-in.
- Deterministic logging and artifact emission.

3. Integration adapters (Go packages)
- Git adapter (replacement for `safe_git.sh` behaviors).
- Secrets adapter (GCP Secret Manager via workload identity).
- Notification adapter (Slack + GitHub status/checks).
- Buildmaster adapter (if still required during transition).
- Cloud deploy adapters (GAE/Cloud Run/Fastly/etc).

## Migration Requirements

### Functional Parity Requirements
- Preserve job entry points and semantics for:
  - Parameters (`GIT_REVISION`, `BASE_REVISION`, `SLACK_CHANNEL`, `JOB_PRIORITY`, etc.).
  - Schedules.
  - Manual trigger support.
  - Approval gates where currently using `input(...)`.
  - Parallel behavior and timeout envelopes.
- Preserve external side effects exactly (deploy operations, tags, version switches, notifications).
- Preserve observability and debugging breadcrumbs (links, summaries, version IDs, actor identity).

### Reliability and Safety Requirements
- At-least parity on rollback safety for deploy jobs.
- Strong concurrency controls for deploy-critical workflows.
- Explicit idempotency for mutating operations (deploy, tag, set-default, merges).
- Retry policies around flaky infra calls (git network, API calls, cloud deploy status polling).
- Failure-mode behavior matching current expectations (alerts, buildmaster status updates, abort handling).

### Security Requirements
- Remove static long-lived Jenkins secret patterns where possible.
- Use GitHub OIDC -> GCP Workload Identity Federation for cloud access.
- Minimize token scopes for GitHub and Slack integrations.
- Preserve secret scoping semantics currently provided by `withSecrets.*`.
- Ensure secrets never appear in logs/artifacts.

### Operational Requirements
- Backward-compatible transition period where Jenkins and GHA can run side-by-side for selected jobs.
- Clear ownership and runbooks for on-call/deployers.
- Fast rollback to prior execution path during migration.

## Mapping: Jenkins Concepts -> GitHub Actions + Go
- Jenkins params -> `workflow_dispatch.inputs` + Go flags/env.
- `addCronSchedule` -> `on.schedule.cron`.
- `disableConcurrentBuilds` -> `concurrency.group` + `cancel-in-progress` policy.
- `onWorker` labels -> self-hosted runner labels / runner groups.
- `withTimeout` -> `timeout-minutes` + Go context deadlines.
- `parallel(...)` -> matrix jobs or parallel goroutines within one job.
- `input(...)` -> protected `environment` approvals or explicit "approval issue/comment" flow.
- `build(job: ...)` -> `workflow_call`, `repository_dispatch`, or `workflow_dispatch` API from Go/action step.
- `notify` wrapper -> Go notifier package + standardized job summary output.
- `withSecrets` -> OIDC auth + runtime secret retrieval in Go.

## Recommended Migration Strategy

### Phase 0: Foundation
- Build shared Go libraries first: config, logging, command exec, secrets, notifications, git helpers.
- Establish standard reusable workflow templates (`workflow_call`) for auth, checkout, and Go runtime.

### Phase 1: Low-risk jobs first
- Migrate Tier 3 scheduled jobs and wrappers.
- Run dual execution (Jenkins + GHA) in report-only mode where possible, compare outputs.

### Phase 2: Medium complexity
- Migrate Tier 2 build/deploy jobs with guarded rollouts.
- Keep Jenkins as fallback trigger for one release window.

### Phase 3: Deploy-critical orchestration
- Migrate Tier 1 jobs after proving primitives under load.
- Introduce explicit approval and rollback controls before cutover.

### Phase 4: Decommission
- Disable Jenkins triggers per job after acceptance criteria pass.
- Archive dashboards/log routing parity checks and update runbooks.

## Open Questions (Options + Pros/Cons)

### 1) Where should Go runner code live?
Option A: In this repo (`jenkins-jobs`) during migration.
- Pros: tight coupling to existing job definitions; easier incremental port.
- Cons: long-term repo purpose drift; mixed legacy/new code noise.

Option B: New dedicated repo for pipeline runtime.
- Pros: clean architecture boundary; reusable by multiple repos.
- Cons: higher initial coordination overhead; cross-repo release/versioning complexity.

### 2) One workflow per former Jenkins job, or consolidated workflows?
Option A: One-for-one workflow mapping.
- Pros: easiest traceability and parity verification.
- Cons: many workflows; duplication risk without good templates.

Option B: Fewer domain workflows with mode flags.
- Pros: less duplication; centralized lifecycle updates.
- Cons: larger blast radius per change; more complex input validation.

### 3) How to implement manual approval gates?
Option A: GitHub Environments with required reviewers.
- Pros: built-in audit trail and policy controls.
- Cons: coarser UX than Jenkins `input`; may need environment design changes.

Option B: Custom Slack/GitHub-comment approval bot flow.
- Pros: can mirror current deploy UX closely.
- Cons: more code and security surface area.

### 4) How to preserve queue priority semantics (`JOB_PRIORITY`)?
Option A: Encode priority through separate runner pools/workflows.
- Pros: simple and explicit.
- Cons: less granular than Jenkins plugin behavior.

Option B: Build custom dispatch scheduler service.
- Pros: closest behavior parity.
- Cons: significant complexity; new service to operate.

### 5) Parallelization model for large test/deploy jobs?
Option A: GitHub matrix jobs.
- Pros: native visibility/retries; per-shard isolation.
- Cons: inter-shard coordination can be complex.

Option B: Single workflow job + Go-managed worker fanout.
- Pros: full control over scheduling logic (can mimic existing flow).
- Cons: less native GHA observability; runner sizing pressure.

### 6) What replaces `safe_git.sh` + workspace sharing?
Option A: Stateless checkout each run (`actions/checkout` + Go git operations).
- Pros: predictable and reproducible.
- Cons: potentially slower for large repos/submodules.

Option B: Self-hosted runners with persistent caches/workspaces.
- Pros: faster repeated operations.
- Cons: cache invalidation and contamination risk; more ops burden.

### 7) Buildmaster integration during transition?
Option A: Keep buildmaster notifications/contract unchanged.
- Pros: lower migration risk to deploy pipeline.
- Cons: preserves legacy coupling longer.

Option B: Move orchestration ownership into GHA and shrink buildmaster role.
- Pros: cleaner future state.
- Cons: larger migration scope and risk.

### 8) Slack notifications: continue through `alertlib` or replace?
Option A: Keep `alertlib` invocation initially.
- Pros: behavior parity and message consistency.
- Cons: legacy dependency carried forward.

Option B: Native Go Slack client + templates.
- Pros: fewer legacy dependencies; easier testing/versioning.
- Cons: requires careful parity work for channels/threads/format.

### 9) Secret access model for GitHub runners?
Option A: GitHub OIDC -> GCP Secret Manager (recommended).
- Pros: no long-lived cloud keys; strong auditability.
- Cons: initial IAM/workload identity setup effort.

Option B: Store cloud keys as GitHub secrets.
- Pros: quick bootstrap.
- Cons: weaker security posture, rotation burden.

### 10) Runner strategy for specialized workloads (current Jenkins worker labels)
Option A: GitHub-hosted runners where possible + self-hosted only when required.
- Pros: lower maintenance footprint.
- Cons: may not satisfy network/tooling constraints.

Option B: Fully self-hosted runner groups mapped from current labels.
- Pros: maximum control and closer behavioral parity.
- Cons: higher operational overhead and capacity management.

## Acceptance Criteria for Cutover
- For each migrated job, at least N successful runs (define per job criticality) with expected side effects.
- Alerting parity validated (channel, thread, severity, links).
- Timeout/retry/cancellation behavior validated under failure injection.
- Deploy-critical jobs validated with staged dry-runs and one supervised production run.
- Rollback procedure documented and tested.

## Immediate Next Steps
1. Decide ownership/location of Go runtime code (Open Question #1).
2. Define canonical workflow template + Go CLI contract for parameters.
3. Pick 3 Tier 3 jobs for pilot migration and dual-run comparison.
4. Implement auth baseline (OIDC to GCP) and secret retrieval pattern.
5. Produce per-job parity checklist (inputs, side effects, alerts, rollback path).
