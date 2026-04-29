# Jenkins Groovy -> GitHub Actions Migration Research

## Scope and Goal
Migrate Jenkins jobs defined in this repository (`jobs/*.groovy` + shared helpers in `vars/*.groovy`) to GitHub Actions workflows hosted in `../webapp`.

Reference implementations already in use:
- `../webapp/.github/workflows/*` (notably `webapp-test.yml`, `gqlgen-update.yml`, `kubejob.yml`, `validate-workflows.yml`)
- `../frontend/.github/workflows/*` (notably reusable workflows, deploy queue workflows, scheduled E2E workflows)
- `../actions/actions/*` shared composite actions (filtering changed files, shared node cache, etc.)

---

## Requirements

### Functional requirements
1. Preserve each Jenkins job's trigger semantics.
- Manual jobs -> `workflow_dispatch` with typed inputs.
- Scheduled jobs -> `on.schedule` cron in UTC (GitHub Actions semantics).
- PR/push checks -> `pull_request`, `push`, `merge_group` where applicable.

2. Preserve runtime behavior and side effects.
- Deploy/build behavior must remain equivalent (GCP deploys, GCS uploads, docker image pushes, buildmaster status updates).
- Existing cross-job orchestration (`build(job: ...)`) must be represented via `workflow_call`, `workflow_dispatch`, or decomposition into a single DAG workflow.

3. Preserve input parameterization.
- Jenkins params (string/boolean/choice) must be represented as workflow inputs with defaults and validation.
- Manual approval prompts (`input(...)`) must be replaced with GitHub-native controls (environment approvals, explicit second dispatch, or flag-based continuation).

4. Preserve notifications and incident signals.
- Slack notifications currently handled via `notify`/`withSecrets` and `alertlib` should be migrated to consistent Actions-based notification steps.

5. Preserve test/build coverage behavior.
- Jenkins `webapp-test` and related test fanout behavior must remain at least equivalent in coverage and failure reporting.

### Security and compliance requirements
1. Replace Jenkins secret-fetch model (`gcloud secrets access` in job shell) with GitHub-native secret access patterns.
- Prefer OIDC + Workload Identity Federation (already used in `webapp`/`frontend` setup actions).
- Use least-privilege permissions per job (`permissions:` blocks).

2. Keep pull_request security boundaries.
- Continue using `pull_request` (not `pull_request_target`) for untrusted PR code unless strictly needed.

3. Restrict network egress where possible.
- Follow `setup` action pattern that applies network allowlists (`Khan/actions@secure-network-v1`) and explicit extra domains.

### Reliability and operability requirements
1. Add explicit `concurrency` groups where Jenkins had singleton/lock semantics.
- Jenkins uses `singleton` and explicit lock behavior in places (e.g., traffic updates). Equivalent GH concurrency/serialization is required.

2. Set timeouts and retries explicitly.
- Port Jenkins `withTimeout` behavior to `timeout-minutes`.
- Preserve retries where they protect flaky infra dependencies.

3. Support rollback and manual control flows.
- Jobs like deploy/rollback/fastly require safe operator workflows with clear guardrails.

4. Preserve artifact/log visibility.
- Keep uploaded artifacts and structured summaries for debugging (pattern present in existing workflows).

### Migration execution requirements
1. Incremental migration with dual-run validation where practical.
2. Feature parity checklist per job family before Jenkins disablement.
3. Ownership and oncall routing defined per migrated workflow.

---

## Current System Architecture (Jenkins)

### Control plane
- Jenkins Groovy pipelines in `jobs/*.groovy`.
- Shared primitives in `vars/*.groovy`:
  - Execution environment selection: `onMaster`, `onWorker`
  - Secret materialization: `withSecrets.*`
  - Notifications: `notify`
  - Buildmaster API integration: `buildmaster`
  - Git safety wrappers: `kaGit` + `safe_git.sh`
  - Timeout wrapper: `withTimeout`
  - Singleton behavior: `singleton`

### Execution model
- Node affinity via Jenkins worker labels (`master`, `build-worker`, `ka-test-ec2`, `znd-worker`, etc.).
- Workspace reuse and repo syncing with `safe_git.sh` wrappers.
- Job chaining through Jenkins `build(job: ...)`.
- Human approval via pipeline `input(...)`.

### External dependencies
- GCP (gcloud, GCS, GKE, Cloud Run/App Engine workflows).
- Slack via alertlib and secrets.
- Buildmaster HTTP API for deploy/test state integration.
- GitHub repos (`webapp`, `frontend`, others) cloned from jobs.

### Notable job patterns observed
- Scheduled maintenance/automation jobs (ownership data, static images, i18n videos, sqlite, etc.).
- Deploy pipeline jobs (`build-webapp`, `deploy-webapp`, `deploy-znd`, `deploy-fastly`, rollback).
- Test fanout jobs (`webapp-test`, `e2e-test`, `go-codecoverage`).
- Automation jobs that commit/push to branches (notably `automated-commits`).

---

## Target System Architecture (GitHub Actions in `webapp`)

### Core pattern
- Workflows under `../webapp/.github/workflows`.
- Reusable workflow composition via `workflow_call` (pattern proven in `frontend`).
- Shared setup and security baseline via `../webapp/.github/actions/setup/action.yml`.

### Identity and secret model
- OIDC auth to GCP using `google-github-actions/auth` + WIF provider.
- GitHub secrets for non-cloud credentials where unavoidable.
- Minimal per-job `permissions` and explicit `id-token: write` only where needed.

### Orchestration model
- Replace Jenkins cross-job calls with one of:
  - A single orchestrator workflow with dependent jobs.
  - Reusable workflows chained with `workflow_call`.
  - Controlled `workflow_dispatch` for operator-driven steps.

### Concurrency and locking model
- Use `concurrency.group` per deploy surface (e.g., set-default traffic updates, znd deploy lanes).
- Use `cancel-in-progress` selectively (typically true for PR checks, false for deploy workflows).

### Notification model
- Prefer direct `slackapi/slack-github-action` and/or existing internal scripts already used in `webapp`/`frontend` workflows.
- Standardize failure/success reporting at workflow end with `if: always()` report jobs.

### Shared implementation assets
- Reuse patterns from:
  - `webapp-test.yml` for test sharding and artifacts.
  - `gqlgen-update.yml` for scheduled + bot-token update workflows.
  - `frontend` reusable workflow patterns (`workflow_call`, setup, report jobs).
- Consider selective reuse of `../actions` composites (`filter-files`, `get-changed-files`, etc.) where they reduce custom scripting.

---

## Suggested Migration Grouping

### Group A: PR/CI checks
- `webapp-test`, `go-codecoverage`, related fast feedback jobs.
- Priority: highest (already partly migrated via `webapp-test.yml`).

### Group B: Scheduled maintenance and content automation
- `webapp-maintenance`, `update-ownership-data`, `update-devserver-static-images`, `update-i18n-lite-videos`, `build-current-sqlite`, `find-failing-taskqueue-tasks`.
- Priority: high; lower blast radius than deploy orchestration.

### Group C: Deploy orchestration and rollback
- `determine-webapp-services`, `build-webapp`, `deploy-webapp`, `deploy-znd`, `deploy-fastly`, `emergency-rollback`, `merge-branches`, `delete-version`.
- Priority: highest risk; migrate after platform primitives are stable.

### Group D: Specialized/legacy jobs
- `demo-district-update-pass`, `qa-metrics`, `test-buildmaster`, etc.
- Priority: evaluate retain/replace/retire first.

---

## Open Questions with Options and Pros/Cons

### 1) Where should deploy orchestration logic live?
Option A: Single large orchestrator workflow in `webapp`.
- Pros: One place to reason about deploy state; simpler visibility for operators.
- Cons: Large YAML complexity; harder reuse/testing; risk of brittle monolith.

Option B: Multiple reusable workflows (`determine`, `build`, `deploy`, `verify`) called by thin orchestrator.
- Pros: Better modularity; easier incremental migration and testing.
- Cons: More interface contracts and outputs to manage.

Decision: We'll go with Option B, with the orchestrator to be built separately from this project.

### 2) How to replace Jenkins `input(...)` approvals?
Option A: GitHub Environments with required reviewers.
- Pros: Native audited approvals; clean UX.
- Cons: Coarser-grained than arbitrary script prompts.

Option B: Split workflow into two dispatchable steps (build then deploy) with explicit artifact/version input.
- Pros: Very explicit operator control; easier rollback and replay.
- Cons: More operational steps; risk of manual mismatch.

Decision: Option B, managed by the external orchestrator (see question 1)

### 3) How to model Jenkins worker labels (special machines)?
Option A: Standardize on github-hosted runners where possible.
- Pros: Simpler ops; less infra maintenance.
- Cons: May not support heavy Docker/GCP/network assumptions or cost profile.

Option B: Keep/expand self-hosted ephemeral runners by class.
- Pros: Better parity with current heavy workloads and custom tooling.
- Cons: More infra ownership and debugging burden.

Decision: use `ephemeral-runner` for everything for now, with comments about the label it was migrated from in case we need to specialize in the future.

### 4) How to integrate with Buildmaster APIs long-term?
Option A: Keep Buildmaster API calls from Actions initially.
- Pros: Fastest parity path; low upstream change.
- Cons: Carries forward coupling and legacy state model.

Option B: Move deploy/test state to GitHub Deployments + Checks over time.
- Pros: Native GitHub observability and control.
- Cons: Larger project; migration of downstream tooling required.

Decision: A for now, with inline comments indicating what we'll need to change in the future.

### 5) How to replace Jenkins `singleton` and lock semantics?
Option A: `concurrency.group` at workflow/job level only.
- Pros: Native and simple.
- Cons: Not equivalent to all lock queue priority behaviors.

Option B: Concurrency + explicit cloud lock (e.g., Redis/Firestore/GCS lock) for critical sections.
- Pros: Precise control for high-risk operations.
- Cons: More complexity and failure modes.

Decision: A

### 6) How to migrate jobs that commit back to branches (`automated-commits`)?
Option A: Push commits directly from workflow (bot token), then auto-open/update PR.
- Pros: Closest to current behavior; low friction.
- Cons: Direct push automation can be risky without branch protections.

Option B: Always create/update PRs, no direct protected-branch pushes.
- Pros: Better review/audit trail.
- Cons: Slower path for routine automation.

Decision: A, see automated-commits workflow in ../frontend

### 7) What to do with likely stale/broken jobs (example: `demo-district-update-pass` TODO notes)?
Option A: Migrate as-is first, then refactor.
- Pros: Completeness and parity.
- Cons: Wastes effort on potentially obsolete workflows.

Option B: Triage first into keep/replace/retire.
- Pros: Better ROI and lower maintenance burden.
- Cons: Requires stakeholder decisions up front.

Decision: Feel free to skip this one, it's no longer needed. Are there any others?

### 8) Notification strategy: alertlib parity vs direct Slack steps
Option A: Keep alertlib usage in scripts.
- Pros: Message parity and existing templates.
- Cons: Preserves Python/secrets complexity.

Option B: Migrate to direct Slack action + lightweight common scripts.
- Pros: Simpler workflows; fewer secret-materialization patterns.
- Cons: Requires message/template reimplementation.

Decision: B, we should follow the patterns in our existing github workflows.

### 9) Cross-repo dependencies during workflows (`frontend`, `internal-services`, `qa-tools`)
Option A: Continue multi-checkout/clone in workflows.
- Pros: Maintains current behavior.
- Cons: Longer run times and more auth/network complexity.

Option B: Extract reusable artifacts/APIs and reduce clone-time dependencies.
- Pros: Faster, cleaner pipelines.
- Cons: Requires additional engineering work outside migration.

Decision: A for now, with comments indicating future work

---

## Immediate Next Steps
1. Build a per-job migration matrix (job -> trigger -> inputs -> secrets -> external systems -> target workflow path).
2. Decide orchestration strategy for Group C (single orchestrator vs modular reusable workflows).
3. Define lock/concurrency policy for deploy-critical operations.
4. Pilot one scheduled automation migration (`update-ownership-data` or `gqlgen`-like job) and one deploy-adjacent migration before bulk porting.
