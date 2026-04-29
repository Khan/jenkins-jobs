# Phase 2 Research: Platform Scaffolding for GitHub Actions

This document captures the Phase 2 requirements and architecture for migration scaffolding in `../webapp/.github/workflows`, building on the higher-level context in `research.md`.

## Scope
Phase 2 covers platform scaffolding only (templates, conventions, and baseline workflow structure). It does not migrate individual Jenkins jobs yet.

Primary outcomes:
- Standard reusable workflow templates for CI/test, scheduled automation, and deploy primitives.
- Baseline workflow skeleton conventions (permissions, timeout, concurrency, runner, setup).
- Standard terminal reporting and Slack notification patterns.

## Requirements

### Functional requirements
1. Provide reusable workflow templates in `../webapp/.github/workflows` for:
- CI/test jobs.
- Scheduled automation jobs.
- Deploy component primitives (`determine`, `build`, `deploy`, `verify`, `rollback`).

2. Keep templates compatible with orchestrator-driven execution.
- Templates must support `workflow_call` for composition.
- Operator entry points should remain compatible with `workflow_dispatch`.

3. Preserve parity constraints from migration standards.
- Scheduled jobs must preserve UTC cron semantics once bound to concrete jobs.
- Multi-checkout patterns must remain possible for jobs that currently depend on cross-repo clones.
- Buildmaster integration must remain possible in deploy templates (with TODO markers for future replacement).

### Security and compliance requirements
1. Enforce least-privilege baseline via explicit `permissions` in all templates.
2. Ensure GCP-authenticated templates are compatible with OIDC/WIF (`id-token: write` only where needed).
3. Keep `pull_request` safety boundaries for CI templates (no `pull_request_target` assumption).
4. Avoid Jenkins-style ad hoc secret-fetch shell patterns in scaffold examples.

### Reliability and operability requirements
1. Standardize `timeout-minutes` defaults per workflow class.
2. Standardize `concurrency` naming and behavior.
- PR/CI: allow cancellation of superseded runs.
- Deploy paths: serialize by environment/surface and disable cancellation in progress.

3. Standardize runner baseline.
- Use `runs-on: [ephemeral-runner]` initially.
- Preserve migration comments indicating original Jenkins label for future runner specialization.

4. Require terminal report + notification pattern.
- Final reporting job must run with `if: always()`.
- Slack success/failure steps should be consistent and reusable.

## Related System Architecture

### Current control plane (source)
- Jenkins pipelines in `jobs/*.groovy` with shared primitives in `vars/*.groovy`.
- Common primitives currently map to needed Actions scaffolding:
- `withTimeout` -> `timeout-minutes`.
- `singleton` -> `concurrency`.
- `onMaster`/`onWorker` -> `runs-on` conventions.
- `notify` -> standardized Slack/report jobs.

### Target control plane (Phase 2 scaffold target)
- Workflows and reusable templates in `../webapp/.github/workflows`.
- Shared setup in `../webapp/.github/actions/setup/action.yml` as default initialization path.
- External orchestrator composes deploy primitives through `workflow_call`.

### Recommended scaffold structure
1. Reusable workflow layer:
- `reusable-ci-test.yml`
- `reusable-scheduled-job.yml`
- `reusable-deploy-determine.yml`
- `reusable-deploy-build.yml`
- `reusable-deploy-execute.yml`
- `reusable-deploy-verify.yml`
- `reusable-deploy-rollback.yml`

2. Entry workflow layer (thin wrappers):
- `workflow_dispatch` wrappers for operator-driven operations.
- Optional cron wrappers for scheduled operations.
- Minimal business logic in wrappers; delegate to reusable workflows.

3. Common job skeleton conventions:
- Explicit `permissions` block.
- Explicit `timeout-minutes`.
- `runs-on: [ephemeral-runner]`.
- Shared setup action call.
- Deterministic `concurrency.group` naming format.
- Final `report` job with `if: always()` and standardized Slack hooks.

## Open Questions for Phase 2

### 1) How strict should template defaults be for `permissions`?
Option A: Start with minimal per-template defaults and require explicit opt-in for extra scopes.
- Pros: Better security posture, easier review.
- Cons: More edits needed when wiring job-specific capabilities.

Option B: Use broader default permissions and tighten later per workflow.
- Pros: Faster initial migration velocity.
- Cons: Higher risk and weaker compliance baseline.

Recommendation: Option A.

### 2) Where should `concurrency.group` naming be defined?
Option A: Central convention documented and repeated in each template.
- Pros: Simple and transparent.
- Cons: Potential drift if teams diverge in string construction.

Option B: Encapsulate naming via reusable inputs/construction helper pattern.
- Pros: Better consistency across workflows.
- Cons: Slightly higher abstraction and debugging overhead.

Recommendation: Option B if practical in YAML constraints; otherwise A with strict lint guidance.

### 3) How should report/Slack logic be reused?
Option A: Inline terminal report + Slack steps in each template.
- Pros: Easy local readability.
- Cons: Duplication and drift risk.

Option B: Shared reusable report workflow/composite action used by templates.
- Pros: Consistent behavior and easier updates.
- Cons: More indirection and interface management.

Recommendation: Option B.

### 4) How should deploy primitives pass state/artifacts?
Option A: Pass values through `workflow_call` outputs only.
- Pros: Clear contracts and low storage complexity.
- Cons: Limited for large payloads/artifacts.

Option B: Use outputs for metadata and artifacts for larger handoff data.
- Pros: Scales to real deploy payloads and logs.
- Cons: Requires strict artifact naming and retention policy.

Recommendation: Option B.

### 5) What should be the initial timeout defaults by workflow class?
Option A: Single timeout default for all templates.
- Pros: Simpler scaffold.
- Cons: Poor fit for heterogeneous workloads.

Option B: Class-based defaults (CI/test, scheduled automation, deploy primitives).
- Pros: Better operational fit and fewer false timeouts.
- Cons: Requires up-front policy decisions.

Recommendation: Option B.

### 6) Should scaffold include bridge-mode support now?
Option A: Include bridge-aware inputs/metadata in scaffolds from day one.
- Pros: Smoother Jenkins -> bridge -> GitHub rollout.
- Cons: Slightly more complexity in templates.

Option B: Add bridge-specific handling only when each job migrates.
- Pros: Keeps scaffolds minimal.
- Cons: Repeated integration effort later.

Recommendation: Option A.

## Proposed Decisions to Confirm Before Implementation
1. Approve reusable modular deploy primitive model (already aligned with `plan.md` + `research.md`).
2. Approve least-privilege permissions baseline and OIDC-first auth model.
3. Approve class-based timeout/concurrency defaults.
4. Approve shared report/Slack abstraction instead of per-workflow inline duplication.
5. Approve bridge-mode compatibility fields in scaffold interfaces.

## Phase 2 Deliverable Checklist
- `phase-2-research.md` (this document).
- Agreed scaffold conventions documented for `../webapp/.github/workflows` implementation.
- Open-question decisions resolved sufficiently to start template creation.
