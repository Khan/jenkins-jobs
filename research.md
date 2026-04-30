# Jenkins Groovy -> GitHub Actions Migration Research

## Scope and Goal
Migrate Jenkins jobs defined in this repository (`jobs/*.groovy` + shared helpers in `vars/*.groovy`) to GitHub Actions workflows hosted in `../webapp`.

**First pass scope — bridge phase only.** The initial implementation will be a "bridge" phase: Jenkins jobs become thin wrappers that dispatch GitHub Actions workflows and wait for results. Jenkins continues to own `notify()` during this phase, which substantially simplifies the GitHub workflows (no need to communicate with Buildmaster from Actions). Full ownership transfer to GitHub Actions is a subsequent phase.

Jobs in scope for this first pass:
- `merge-branches`: merges the deployer's branch with master and, potentially, the first deployer in the queue.
- `webapp-test`: runs unit tests and linters.
- `build-webapp`: builds and deploys new Cloud Run revisions.
- `e2e-test`: runs E2E tests on pre-prod and prod (already ported to GitHub Actions).
- `deploy-webapp`: performs traffic migrations, deploys Fastly and other infrastructure.
- `emergency-rollback`: handles rollbacks of bad regressions (rarely used).
- `delete-versions`: keeps revision count healthy so deployments don't become blocked.
- `deploy-znd`: not strictly required for deployment but sometimes needed for pre-queue verification.

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
- Manual approval prompts (`input(...)`) do **not** need to be ported. They exist to handle unusual situations that won't apply to the new GitHub-based system.

4. Preserve notifications and incident signals.
- In the bridge phase, Jenkins continues to own `notify()` — GitHub Actions workflows do not need to re-implement those notification steps.
- However, some jobs send Slack messages independently of `notify()`. These must be ported to GitHub Actions (using `slackapi/slack-github-action`) even in the bridge phase.

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
- `delete-versions` must share a concurrency group with the traffic migration step (extracted from `deploy-webapp`) — it is not safe to delete versions while traffic is being migrated.

2. Set timeouts and retries explicitly.
- Port Jenkins `withTimeout` behavior to `timeout-minutes`.
- Preserve retries where they protect flaky infra dependencies.

3. Support rollback and manual control flows.
- Jobs like deploy/rollback/fastly require safe operator workflows with clear guardrails.

4. Preserve artifact/log visibility.
- Keep uploaded artifacts and structured summaries for debugging (pattern present in existing workflows).

### Migration execution requirements
1. Incremental migration using a staged cutover per job: `jenkins` -> `bridge` (Jenkins dispatches Actions) -> `github` (GitHub-owned trigger/execution).
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
- **Bridge phase**: Jenkins retains ownership of `notify()`. GitHub Actions workflows do not need to re-implement those notification steps. However, any Slack messages sent outside of `notify()` directly from a job must be ported to `slackapi/slack-github-action` in the corresponding GitHub Actions workflow.
- **Post-bridge**: Migrate remaining `notify()`-based alerts to `slackapi/slack-github-action` and/or existing internal scripts already used in `webapp`/`frontend` workflows. Standardize failure/success reporting at workflow end with `if: always()` report jobs.

### Shared implementation assets
- Reuse patterns from:
  - `webapp-test.yml` for test sharding and artifacts.
  - `gqlgen-update.yml` for scheduled + bot-token update workflows.
  - `frontend` reusable workflow patterns (`workflow_call`, setup, report jobs).
- Consider selective reuse of `../actions` composites (`filter-files`, `get-changed-files`, etc.) where they reduce custom scripting.

---

## Suggested Migration Grouping

This grouping covers only the jobs in scope for the first pass (bridge phase). Other jobs are deferred.

### Group A: PR/CI checks
- `webapp-test` (already partly migrated via `webapp-test.yml`).
- Priority: highest.

### Group B: Deploy orchestration and rollback
- `merge-branches`, `build-webapp`, `deploy-webapp`, `emergency-rollback`, `delete-versions`, `deploy-znd`.
- Priority: highest risk; migrate after platform primitives are stable.
- Note: `deploy-webapp` must be structured as (at least) three separate jobs within a single workflow: `pre-set-default`, `set-default`, and `post-set-default`, executed in series.
- Note: `delete-versions` and the traffic migration step of `deploy-webapp` must share a concurrency group.

### Group C: E2E tests
- `e2e-test` (already ported to GitHub Actions — verify integration with bridge pattern).
- Priority: medium.

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
Decision: No replacement needed. These prompts exist to handle unusual situations that won't apply in the new GitHub-based system. They will not be ported.

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

Decision: A

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
4. Define bridge-mode implementation pattern (dispatch, status reporting, rollback switch, scheduler ownership).
5. Pilot one scheduled automation migration (`update-ownership-data` or `gqlgen`-like job) and one deploy-adjacent migration before bulk porting.

## Additional Open Questions (Pending Decisions)

### 10) Should `make-allcheck.groovy` be skipped?
Option A: Skip migration.
- Pros: Avoids migrating a Jenkins-only orchestrator if equivalent orchestration already exists or will exist in the new system.
- Cons: Could remove a familiar manual entrypoint unless replaced.

Option B: Migrate as a lightweight GitHub Actions orchestrator wrapper.
- Pros: Preserves operator workflow for running combined test suites from one trigger.
- Cons: May duplicate orchestration responsibility with the external orchestrator.

Decision: A

### 11) Should `qa-metrics.groovy` be skipped?
Option A: Skip migration.
- Pros: Reduces scope for non-deploy-critical jobs with external repo dependencies.
- Cons: Loss of automated QA metrics reporting unless replaced elsewhere.

Option B: Migrate with minimal changes.
- Pros: Preserves current behavior and reporting continuity.
- Cons: Keeps cross-repo clone/runtime complexity and maintenance burden.

Decision: B, with a note to move the workflow to the qa-tools repo

### 12) Ownership boundary for deploy workflows vs external orchestrator
Option A: `webapp` owns reusable deploy workflows; orchestrator repo only dispatches/calls them.
- Pros: Deploy implementation stays close to app code and infra scripts.
- Cons: Requires coordination across repos for orchestration changes.

Option B: External orchestrator repo owns most deploy logic; `webapp` keeps only thin entrypoints.
- Pros: Centralized orchestration logic and policy.
- Cons: Logic drifts away from code it deploys; higher cross-repo coupling.

Decision: A

### 13) Canonical bot/token strategy for writeback workflows in `webapp`
Option A: Standardize on `KHAN_ACTIONS_BOT_TOKEN` (same pattern as `frontend`).
- Pros: Consistent implementation and easier reuse of existing patterns.
- Cons: Requires confirming scopes and secret availability for all writeback cases.

Option B: Use multiple purpose-specific tokens/secrets per workflow class.
- Pros: Potentially tighter least-privilege partitioning.
- Cons: More secret management overhead and configuration complexity.

Decision: A

### 14) Cron fidelity during migration
Option A: Preserve exact Jenkins cadence (translated to UTC cron in Actions).
- Pros: Behavioral parity and simpler stakeholder expectations.
- Cons: May keep suboptimal schedule choices.

Option B: Allow schedule adjustments during migration.
- Pros: Opportunity to reduce contention/cost and align with new runner behavior.
- Cons: Harder parity validation and possible expectation drift.

Decision: A, with comments indicating alternatives that might be preferable

### 15) Cutover strategy per migrated job
Option A: Staged bridge migration (`jenkins` -> `bridge` -> `github`) with per-job rollback switch.
- Pros: Lower risk, preserves existing operator entrypoint during transition, and provides clean fallback.
- Cons: Adds temporary wrapper logic and requires explicit scheduler/duplication controls.

Option B: Immediate switch per job once workflow is merged.
- Pros: Faster migration and less temporary operational complexity.
- Cons: Higher risk of unnoticed regressions at cutover and weaker rollback ergonomics.

Decision: A
