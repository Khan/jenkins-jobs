# Migration Matrix

**Phase 1 output — source of truth for job disposition and scope lock.**

Generated: 2026-04-29

---

## Summary

| Disposition | Count |
|---|---|
| `migrate` | 21 |
| `migrate-later` | 6 |
| `skip` | 2 |
| **Total** | **29** |

---

## Quick-Reference Table

| Source File | Job Name | Trigger | Disposition | Phase Group |
|---|---|---|---|---|
| build-and-deploy-publish-worker.groovy | build-and-deploy-publish-worker | manual | migrate-later | C (Deploy) |
| build-current-sqlite.groovy | build-current-sqlite | schedule | migrate-later | B (Scheduled) |
| build-publish-image.groovy | build-publish-image | manual | migrate | C (Deploy) |
| build-webapp.groovy | build-webapp | chained | migrate | C (Deploy) |
| delete-version.groovy | delete-version | manual | migrate | C (Deploy) |
| demo-district-update-pass.groovy | demo-district-update-pass | schedule | migrate-later | D (Other) |
| deploy-alert-context.groovy | deploy-alert-context | manual | migrate | C (Deploy) |
| deploy-buildmaster.groovy | deploy-buildmaster | manual | migrate | C (Deploy) |
| deploy-fastly.groovy | deploy-fastly | manual | migrate-later | C (Deploy) |
| deploy-webapp_slackmsgs.groovy | *(helper module, not a job)* | — | skip | — |
| deploy-webapp.groovy | deploy-webapp | manual/chained | migrate-later | C (Deploy) |
| deploy-znd.groovy | deploy-znd | manual | migrate | C (Deploy) |
| determine-webapp-services.groovy | determine-webapp-services | chained | migrate | C (Deploy) |
| e2e-test.groovy | e2e-test | manual/chained | migrate | A (CI) |
| emergency-rollback.groovy | emergency-rollback | manual | migrate | C (Deploy) |
| find-failing-taskqueue-tasks.groovy | find-failing-taskqueue-tasks | schedule | migrate | B (Scheduled) |
| firstinqueue-priming.groovy | firstinqueue-priming | chained | migrate | C (Deploy) |
| go-codecoverage.groovy | go-codecoverage | manual | migrate | A (CI) |
| make-allcheck.groovy | make-allcheck | schedule | **skip** | — |
| merge-branches.groovy | merge-branches | chained | migrate | C (Deploy) |
| notify-znd-owners.groovy | notify-znd-owners | manual | migrate | D (Other) |
| qa-metrics.groovy | qa-metrics | manual | migrate | D (Other) |
| test-buildmaster.groovy | test-buildmaster | manual | migrate | C (Deploy) |
| update-devserver-static-images.groovy | update-devserver-static-images | schedule | migrate | B (Scheduled) |
| update-i18n-lite-videos.groovy | update-i18n-lite-videos | schedule | migrate | B (Scheduled) |
| update-ownership-data.groovy | update-ownership-data | schedule | migrate | B (Scheduled) |
| update-translation-pipeline.groovy | update-translation-pipeline | manual | migrate | C (Deploy) |
| webapp-maintenance.groovy | webapp-maintenance | schedule | migrate | B (Scheduled) |
| webapp-test.groovy | webapp-test | manual/chained | migrate-later | A (CI) |

---

## Job Chain Map

Direct `build(job:...)` calls and buildmaster-orchestrated chains:

```
make-allcheck ──► webapp-test (parallel)
              └─► e2e-test (parallel)

emergency-rollback ──► e2e-test (conditional, non-dry-run)

buildmaster orchestrates:
  merge-branches
  determine-webapp-services
  build-webapp
  deploy-webapp
  e2e-test (smoke test)
  firstinqueue-priming ──► e2e-test (internal parallel)
```

`deploy-webapp_slackmsgs.groovy` is `load()`-ed (not `build()`-ed) by both `deploy-webapp.groovy` and `build-webapp.groovy` for Slack message templates.

---

## Detailed Entries

---

### build-and-deploy-publish-worker — `migrate-later`

- **Source file**: `build-and-deploy-publish-worker.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`) — webapp branch/commit for docker image build
  - `ZND_NAME` (string, default: `""`) — ZND name to append to docker image
  - `VALIDATE_COMMIT` (boolean, default: `true`) — validate commit safety before publishing
  - `DEPLOYER_EMAIL` (string, default: `""`) — required @khanacademy.org email for production
- **Secrets/auth**:
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
  - `Slack__API_token_for_alertlib` via GCP Secret Manager
  - `khan_actions_bot_github_personal_access_token__Repository_Status___Deployments__repo_status__repo_deployment__` via GCP Secret Manager
- **External systems**: GCP (Cloud Secrets, GCE, Cloud Logging), GitHub (Khan/webapp), Docker, Slack, Go CLI
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onWorker("build-worker", "60m")`, `input("Deploy to production?")` gate (line 71), `withTimeout('1h')` for git checkout
- **Rationale**: Manual approval gate (`input()`) and complex publish-worker deploy process needs careful mapping to GHA environment approvals; assess Go deploy script and publish validation flow before scheduling.
- **Notes**: Extracts `PUBLISH_VERSION` from build script output via regex. Validates deployer email format. Docker prune if disk < 1.5 GB.

---

### build-current-sqlite — `migrate-later`

- **Source file**: `build-current-sqlite.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `H H * * 0` → UTC equivalent: `0 3 * * 0` (Sundays, arbitrary hour)
- **Inputs**:
  - `CURRENT_SQLITE_BUCKET` (string, default: `gs://ka_dev_sync`)
  - `GIT_REVISION` (string, default: `master`)
- **Secrets/auth**:
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
  - `Slack__API_token_for_alertlib` via GCP Secret Manager (`khan-academy` project)
- **External systems**: GCP (GCS upload of `dev_datastore.tar.gz`, Cloud Logging, Secret Manager, Compute Engine), GitHub (Khan/webapp + Khan/frontend), Slack (#infrastructure), Docker (datastore-emulator container)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onWorker("build-worker", "1h")`; comment notes "hefty resources"
- **Rationale**: Heavy resource consumer with complex Docker/GCP environment setup (SSH git ops, `docker exec`, Go CLI); needs infrastructure assessment before migration.
- **Notes**: Notification to `#infrastructure` on all statuses. No Jenkins triggers beyond schedule.

---

### build-publish-image — `migrate`

- **Source file**: `build-publish-image.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`)
  - `ZND_NAME` (string, default: `""`)
  - `VALIDATE_COMMIT` (boolean, default: `true`)
- **Secrets/auth**:
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
  - `Slack__API_token_for_alertlib` via GCP Secret Manager
- **External systems**: GCP, Docker (image build + `docker image prune -af`), GitHub (Khan/webapp), Slack (#cp-eng)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onWorker("build-worker", "30m")`
- **Rationale**: Self-contained, clear parameters, no downstream job dependencies.
- **Notes**: Conditionally runs `services/content-editing/publish/tools/validate_commit_for_publish.sh`. Docker prune if disk < 1500 MB.

---

### build-webapp — `migrate`

- **Source file**: `build-webapp.groovy`
- **Trigger type**: chained (buildmaster dispatches)
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**) — SHA1 to deploy
  - `BASE_REVISION` (string, default: `""`)
  - `SERVICES` (string, default: `"auto"`)
  - `ALLOW_SUBMODULE_REVERTS` (boolean, default: `false`)
  - `FORCE` (boolean, default: `false`)
  - `SKIP_PRIMING` (boolean, default: `false`)
  - `CLEAN` (choice: `some/most/all/none`, default: `"some"`)
  - `DEPLOYER_USERNAME` (string, default: `""`)
  - `SLACK_CHANNEL` (string, default: `"#1s-and-0s-deploys"`)
  - `SLACK_THREAD` (string, default: `""`)
  - `REVISION_DESCRIPTION` (string, default: `""`)
  - `BUILDMASTER_DEPLOY_ID` (string, default: `""`)
  - `JOB_PRIORITY` (string, default: `"6"`)
- **Secrets/auth**:
  - `Slack__API_token_for_alertlib`, `google_api_service_account__for_alertlib_`, `BUILDMASTER_TOKEN`, `khan_actions_bot_github_personal_access_token__Repository_Status___Deployments__repo_status__repo_deployment__` — all via GCP Secret Manager
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
- **External systems**: GCP (Cloud Datastore/AppEngine, Cloud Run, GCS, gcloud), Buildmaster API (`https://buildmaster-526011289882.us-central1.run.app`), Slack, GitHub (Khan/webapp)
- **Jenkins behaviors**: `allowConcurrentBuilds`, `resetNumBuildsToKeep(500)`, `onWorker('build-worker', '4h')`, nested timeouts (1h merge, 1h init, 150m deploy, 5m changelog, 20m failure), `parallel()` for multi-service deploys, `BuildUser` wrapper, `load("deploy-webapp_slackmsgs.groovy")`
- **Rationale**: Critical deploy pipeline; dependencies map cleanly to GHA workflow_call pattern. Buildmaster integration kept as-is per plan.
- **Notes**: Parallel deploy execution across services. Retry logic on buildmaster HTTP calls.

---

### delete-version — `migrate`

- **Source file**: `delete-version.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `SERVICE_VERSIONS` (string, **required**) — JSON object `{"service": ["version1", ...], ...}`
- **Secrets/auth**:
  - `GOOGLE_APPLICATION_CREDENTIALS` → `${env.HOME}/jenkins-deploy-gcloud-service-account.json`
  - `BOTO_CONFIG` → `${env.HOME}/.boto`
  - Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/webapp, master), GCP App Engine (`deploy/delete_gae_versions.py`), Slack (#1s-and-0s-deploys), Cloud Logging
- **Jenkins behaviors**: `onMaster('25m')`, `lock(resource: 'update-traffic-lock', priority: 10)`, `resetNumBuildsToKeep(9000)`, parallel watchdog for abort detection
- **Rationale**: Well-defined operator tool; lock priority and validation logic translate directly to concurrency controls.
- **Notes**: Strict JSON parameter validation (type, list elements, string versions). Lock priority 10 coordinates with deploy-webapp (priority 20). Uses `JsonSlurperClassic` for thread safety.

---

### demo-district-update-pass — `migrate-later`

- **Source file**: `demo-district-update-pass.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `H H 1 * *` → UTC equivalent: `0 3 1 * *` (first day of month)
- **Inputs**:
  - `CYPRESS_GIT_REVISION` (string, default: `"update-psw"`)
  - `SLACK_CHANNEL` (string, default: `"#demo-district-logs"`)
  - `TEST_RETRIES` (string, default: `"1"`)
- **Secrets/auth**: GCP Cloud Secrets, `Slack__API_token_for_alertlib`, `jenkins-deploy-gcloud-service-account.json`
- **External systems**: GitHub (Khan/webapp), GCP, Slack, Docker, Cypress
- **Jenkins behaviors**: `allowConcurrentBuilds`, `onWorker("ka-test-ec2", "4d")`
- **Rationale**: **⚠ BROKEN JOB** — code comments (lines 4–6) state: "This job is broken due to the removal of `services/static`. It will need to be updated to interface with the frontend repo instead of webapp." Migrate only after the underlying job is fixed/redesigned.
- **Notes**: Cypress test path `javascript/districts-package/__e2e-tests__/change-pswd.spec.ts`. Branch `update-psw` may no longer exist.

---

### deploy-alert-context — `migrate`

- **Source file**: `deploy-alert-context.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**: none
- **Secrets/auth**:
  - AWS credentials from `../aws_access_key` / `../aws_secret` (pre-provisioned workspace files, not Jenkins Credentials API)
  - `BOTO_CONFIG`, `GOOGLE_APPLICATION_CREDENTIALS`
  - Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/alert-context), AWS Lambda (deployed via `make deploy`), Slack (#1s-and-0s-deploys), GCP Cloud Logging
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster()` with 2h timeout, `withTimeout('15m')` (deps), `withTimeout('1h')` (deploy)
- **Rationale**: Standard deploy job with make-based execution and clear integrations.
- **Notes**: AWS credentials come from parent workspace directory installed by Khan/aws-config setup — must provision these as GHA secrets instead. Python project with make targets.

---

### deploy-buildmaster — `migrate`

- **Source file**: `deploy-buildmaster.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_BRANCH` (string, default: `"master"`)
  - `GCLOUD_PROJECT` (choice: `khan-test` / `khan-internal-services`, default: `khan-test`)
  - `SERVICE_OR_JOB` (choice: `all` / `buildmaster` / `reaper-job` / `trigger-reminders-job` / `warm-rollback-job` / `migrate-db` / `generate-db-migration-scripts`, default: `all`)
- **Secrets/auth**:
  - `jenkins-deploy-gcloud-service-account.json`, `Slack__API_token_for_alertlib`, `khan_actions_bot_...`, `BUILDMASTER_TOKEN` — all via GCP Secret Manager
- **External systems**: GCP (gcloud, project deployments), GitHub (Khan/buildmaster2), Buildmaster API, Slack (#infrastructure-deploys, #deploy-support-log), Cloud Logging
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('90m')`, `withTimeout('15m')` for deploy step
- **Rationale**: Clean parameterized deploy with no pipeline dependencies.
- **Notes**: Non-master branches can only deploy to `khan-test`. DB migration operations have branch restrictions. Sender: "Mr Meta Monkey" `:monkey_face:`. Requires `safe_git.sh` via `kaGit.checkoutJenkinsTools()`.

---

### deploy-fastly — `migrate-later`

- **Source file**: `deploy-fastly.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**, default: `""`)
  - `SERVICE` (choice: `khanacademy-org-vcl` / `khanacademy-org-compute` / `blog` / `content-property` / `international` / `kasandbox` / `kastatic` / `khan-co` / `sendgrid`)
  - `TARGET` (choice: `test` / `staging` / `prod`)
  - `CLEAN` (choice: `none` / `all`)
- **Secrets/auth**:
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
  - `BOTO_CONFIG`
  - Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/webapp), GCP / Python virtualenv, Slack (#fastly, #whats-happening), Make targets (`make deploy-${TARGET}`, `make set-default-${TARGET}`, `make active-version-${TARGET}`)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('30m')`, `input("Diff looks good?")` gate for non-compute services, timeouts per stage (60m installDeps, 15m deploy, 5m setDefault)
- **Rationale**: Production Fastly deployment with manual diff approval gate and audit file write (`deployed_versions.txt`); needs env protection rules and equivalent audit logging in target system.
- **Notes**: VCL services show ANSI-colored diff before approval; Compute@Edge services skip the diff step. References `sync-start/end:fastly-deploys-file` markers for `fastly_notifier.py`. Git HEAD must be ahead of master for prod deploys.

---

### deploy-webapp_slackmsgs — `skip`

- **Source file**: `deploy-webapp_slackmsgs.groovy`
- **Trigger type**: N/A — not a job; utility module `load()`-ed by other jobs
- **Cron schedule**: none
- **Rationale**: Groovy helper file containing 10 Slack message templates (`ROLLING_BACK`, `ROLLED_BACK_TO_BAD_VERSION`, `ROLLBACK_FAILED`, `JUST_DEPLOYED`, `SETTING_DEFAULT`, `VERSION_NOT_CHANGED`, `FAILED_MERGE_TO_MASTER`, `FAILED_WITHOUT_ROLLBACK`, `FAILED_WITH_ROLLBACK`, `SUCCESS`, `BUILDMASTER_OUTAGE`). No independent execution; migrated as data inline into their parent workflows.
- **Notes**: Uses Python-style `%(varname)s` interpolation. Imported by `deploy-webapp.groovy` and `build-webapp.groovy`.

---

### deploy-webapp — `migrate-later`

- **Source file**: `deploy-webapp.groovy`
- **Trigger type**: manual / chained (buildmaster)
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**)
  - `SERVICES` (string, default: `"auto"`)
  - `MONITORING_TIME` (string, default: `"5"`)
  - `WAIT_LONGER` (boolean, default: `false`)
  - `SKIP_PRIMING` (boolean, default: `false`)
  - `CLEAN` (choice: `some/most/all/none`, default: `"some"`)
  - `DEPLOYER_USERNAME`, `PRETTY_DEPLOYER_USERNAME`, `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID` (strings, default: `""`)
  - `JOB_PRIORITY` (string, default: `"6"`)
- **Secrets/auth**:
  - `Slack__API_token_for_alertlib`, `google_api_service_account__for_alertlib_`, `khan_actions_bot_...`, `BUILDMASTER_TOKEN` — all via GCP Secret Manager
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
- **External systems**: GCP App Engine (traffic migration via `deploy/set_default.py`), GCS, Slack (#1s-and-0s-deploys, #dev-support-stream, #infrastructure-alerts), Buildmaster, GitHub (webapp), Cloud Logging, Stackdriver (`deploy/monitor.py`), Secret Manager
- **Jenkins behaviors**: `disableConcurrentBuilds`, `resetNumBuildsToKeep(500)`, `lock(resource: 'update-traffic-lock', priority: 20)`, `onMaster('4h')`, multiple nested timeouts (1h, 60m, 10m, 40m, 120m), `parallel()` for promotion+monitoring+smoke, `input()` gates (ConfirmE2eSuccess, ConfirmSetDefaultPrompt), `load("deploy-webapp_slackmsgs.groovy")`
- **Rationale**: Critical production orchestrator with distributed locking, user approval gates, rollback logic, buildmaster polling, and git tag creation — highest-risk migration; requires Phase 5 careful planning.
- **Notes**: Custom `AbortDeployJob` exception for stuck-job handling. Git tag created with SERVICES list and VERSION_DICT as JSON metadata. Buildmaster fallback to manual confirmation on outage. Post-failure survey via Google Forms.

---

### deploy-znd — `migrate`

- **Source file**: `deploy-znd.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**)
  - `VERSION` (string, **required**) — release name; `znd-YYMMDD-username-` prefix auto-added; max 63 chars per DNS component
  - `SERVICES` (string, default: `"auto"`)
  - `CLEAN` (choice: `some/most/all/none`, default: `"some"`)
  - `PRIME` (boolean, default: `false`)
  - `SLACK_CHANNEL` (string, default: `"#1s-and-0s-deploys"`)
  - `SLACK_THREAD` (string, default: `""`)
- **Secrets/auth**:
  - Slack alertlib + Stackdriver via GCP Secret Manager
  - `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
  - `BUILD_USER_ID` via `BuildUser` wrapper
- **External systems**: GCP (Cloud Run, App Engine, Secret Manager, Cloud Logging, gcloud), GitHub (Khan/webapp), Slack, Python deploy scripts (`deploy/should_deploy.py`, `deploy/upload_queues.py`, `deploy/upload_pubsub.py`, `deploy/upload_graphql_safelist.py`)
- **Jenkins behaviors**: `allowConcurrentBuilds`, `withTimeout('150m')` + `withTimeout('1h')` (merge-from-master), `onWorker('znd-worker', '3h')`, `parallel()` for multi-service + GraphQL safelist, `notify(BUILD START/FAILURE/UNSTABLE/ABORTED)`
- **Rationale**: Core developer workflow tool with well-defined parameters and no production traffic management.
- **Notes**: Merges master into target revision before deploy. `load("deploy-webapp_slackmsgs.groovy")`. Deployments prefixed `prod-znd-*`.

---

### determine-webapp-services — `migrate`

- **Source file**: `determine-webapp-services.groovy`
- **Trigger type**: chained (buildmaster)
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**)
  - `BASE_REVISION` (string, default: `""`)
  - `DEPLOYER_USERNAME`, `SLACK_CHANNEL` (#1s-and-0s-deploys), `SLACK_THREAD`, `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID` (strings, defaults: `""`)
  - `JOB_PRIORITY` (string, default: `"6"`)
- **Secrets/auth**: `BUILDMASTER_TOKEN`, Slack alertlib via GCP Secret Manager; `jenkins-deploy-gcloud-service-account.json`; `BUILD_USER_ID` via BuildUser
- **External systems**: GitHub (Khan/webapp), Slack, GCP (Cloud Logging, Secret Manager, GCE metadata), Buildmaster (REST `buildmaster.notifyServices()`)
- **Jenkins behaviors**: `allowConcurrentBuilds`, `onWorker('build-worker', '1h')`, retry in git/buildmaster calls
- **Rationale**: Informational-only step; clean dependencies, straightforward mapping to reusable workflow.
- **Notes**: Calls `deploy/should_deploy.py` for "auto" service detection. No downstream `build(job:...)` calls.

---

### e2e-test — `migrate`

- **Source file**: `e2e-test.groovy`
- **Trigger type**: manual / chained
- **Cron schedule**: none
- **Inputs**:
  - `URL` (string, default: `"https://www.khanacademy.org"`)
  - `TEST_TYPE` (choice: `all/deploy/custom`) — **dummy for buildmaster compat**
  - `TESTS_TO_RUN` (string, default: `""`) — **dummy**
  - `SLACK_CHANNEL` (string, default: `"#1s-and-0s-deploys"`), `SLACK_THREAD` (string, default: `""`)
  - `NUM_WORKER_MACHINES` (string, default: `"30"`)
  - `USE_FIRSTINQUEUE_WORKERS` (boolean, default: `false`) — **hardcoded ignored**
  - `GIT_REVISION` (string, default: `"master"`)
  - `DEPLOYER_USERNAME`, `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID`, `SET_SPLIT_COOKIE`, `EXPECTED_VERSION`, `EXPECTED_VERSION_SERVICES` — misc/dummy params
  - `JOB_PRIORITY` (string, default: `"6"`)
- **Secrets/auth**:
  - `jenkins_github_webapp_e2e_workflow_runner_token` (GCP Secret Manager, masked)
  - `Slack_api_token_for_slack_owl` (GCP Secret Manager)
  - Buildmaster token (via buildmaster module)
- **External systems**: GCP (gcloud, Secret Manager, SA), GitHub (Khan/webapp), LambdaTest (up to 30 parallel workers), Slack, Buildmaster, GCS, pnpm/tsx
- **Jenkins behaviors**: `allowConcurrentBuilds`, `resetNumBuildsToKeep(350)`, `onWorker('ka-test-ec2', '5h')`, parallel watchdog+main for abort detection
- **Rationale**: Wrapper that dispatches to a GitHub Actions workflow via `tools/notify-workflow-status.ts`; migration mostly moves the dispatch layer.
- **Notes**: `USE_FIRSTINQUEUE_WORKERS` param is exposed but hardcoded-ignored. `BUILD_NAME` = URL + build number for Slack uniqueness. Detects "production" vs "non-default" based on URL matching `https://www.khanacademy.org`.

---

### emergency-rollback — `migrate`

- **Source file**: `emergency-rollback.groovy`
- **Trigger type**: manual (also triggered from Slack via `sun: emergency rollback` in `#1s-and-0s-deploys`)
- **Cron schedule**: none (comment notes cron scheduling managed by buildmaster)
- **Inputs**:
  - `JOB_PRIORITY` (string, default: `"1"`)
  - `DRY_RUN` (boolean, default: `false`)
  - `ROLLBACK_TO` (string, default: `""`) — target version tag (e.g., `gae-181217-1330-b18f83d38a3d`)
  - `BAD_VERSION` (string, default: `""`) — version to mark as bad
- **Secrets/auth**: Slack alertlib + Stackdriver via `withSecrets.slackAlertlibOnly()` and `withSecrets.slackAndStackdriverAlertlibOnly()`; `jenkins-deploy-gcloud-service-account.json`
- **External systems**: Slack (#1s-and-0s-deploys, #infrastructure-platform on failure), GCP Cloud Logging, Stackdriver, GitHub (Khan/webapp), App Engine (`deploy/rollback.py`)
- **Jenkins behaviors**: `onMaster(node("master"))`, 30m timeout each stage, `build(job: '../deploy/e2e-test', ...)` (conditional, non-dry-run only)
- **Rationale**: High-priority operational tool; minimal options by design to reduce error during outages.
- **Notes**: Tag validation: versions must start with `gae-`. Includes `make clean_pyc`. Python3 virtualenv from `webapp/deploy`. Post-rollback e2e test chain only when `!DRY_RUN`.

---

### find-failing-taskqueue-tasks — `migrate`

- **Source file**: `find-failing-taskqueue-tasks.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `0 7 * * 1` (Mondays 7:00 AM UTC)
- **Inputs**:
  - `SLACK_CHANNEL` (string, default: `"#infrastructure"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`; `Slack__API_token_for_alertlib` via GCP Secret Manager
- **External systems**: GCP (Secret Manager, Cloud Logging), GitHub (Khan/webapp, master), Slack
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('1h')`, `notify(FAILURE/UNSTABLE/ABORTED)`
- **Rationale**: Simple scheduled job running a single Python script with standard notification.
- **Notes**: `BOTO_CONFIG` env var set. Slack sender: "Taskqueue Totoro" `:totoro:`. Runs `dev/tools/failing_taskqueue_tasks.py`.

---

### firstinqueue-priming — `migrate`

- **Source file**: `firstinqueue-priming.groovy`
- **Trigger type**: chained (invoked by first-in-queue deploy jobs only)
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`)
  - `NUM_WORKER_MACHINES` (string, default: `onWorker.defaultNumTestWorkerMachines()`)
  - `REVISION_DESCRIPTION` (string, default: `""`)
  - `BUILDMASTER_DEPLOY_ID` (string, default: `""`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/webapp), GCE (ka-firstinqueue-ec2 workers), Cloud Logging
- **Jenkins behaviors**: `allowConcurrentBuilds`, `onWorker('ka-firstinqueue-ec2', '1h')`, `parallel("sync-webapp", "e2e-test")`, 5m git resolution timeout
- **Rationale**: Best-effort priming utility with no failure notifications and clear parallel structure.
- **Notes**: Resolves full SHA1 before parallel execution to ensure all nodes work at same commit. `make clean_pyc` in setup. No explicit Slack/buildmaster handlers — failures are silently dropped by design.

---

### go-codecoverage — `migrate`

- **Source file**: `go-codecoverage.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`
- **External systems**: GitHub (Khan/webapp), GCP (service account), Jenkins workers (`big-test-worker` label)
- **Jenkins behaviors**: `allowConcurrentBuilds`, `resetNumBuildsToKeep(15)`, `onWorker('big-test-worker', '5h')`, `catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE')`, `publishCoverage(adapters: [coberturaAdapter('coverage.xml')], sourceFileResolver: STORE_LAST_BUILD)`
- **Rationale**: Standard coverage reporting job; test failures are intentionally non-blocking.
- **Notes**: Uses `go-acc` for accurate coverage including integration tests. Cobertura XML generated via `gocover-cobertura`. Report stored as `STORE_LAST_BUILD` due to ~1 GB webapp size. Keeps only 15 builds for trend line.

---

### make-allcheck — `skip`

- **Source file**: `make-allcheck.groovy`
- **Trigger type**: schedule, chained
- **Cron schedule**: `0 2 * * 1-5` (weekdays 2:00 AM UTC)
- **Rationale**: Per `plan.md` Phase 1 / Phase 6: "Skip migration of `make-allcheck.groovy`." Orchestrator job that chains `webapp-test` and `e2e-test`; its scheduling function will be inherited by the migrated child jobs.
- **Notes**: Chains `../deploy/webapp-test` + `../deploy/e2e-test` in parallel (6 webapp workers, 5 e2e workers). `disableConcurrentBuilds`, `onMaster('5h')`.

---

### merge-branches — `migrate`

- **Source file**: `merge-branches.groovy`
- **Trigger type**: chained (buildmaster)
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISIONS` (string, **required**) — plus-separated commit-ishes
  - `COMMIT_ID` (string, **required**) — buildmaster commit ID
  - `SLACK_CHANNEL` (string, default: `"#1s-and-0s-deploys"`), `SLACK_THREAD` (string, default: `""`)
  - `JOB_PRIORITY` (string, default: `"6"`), `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID`
- **Secrets/auth**: `BUILDMASTER_TOKEN`, `Slack__API_token_for_alertlib`, `khan_actions_bot_...`, `google_api_service_account__for_alertlib_` — all via GCP Secret Manager; `jenkins-deploy-gcloud-service-account.json`; `BOTO_CONFIG`
- **External systems**: GitHub (Khan/webapp — clone, merge, push tags), GCP Secret Manager, Buildmaster (PATCH `/commits/merge`), Slack, Cloud Logging
- **Jenkins behaviors**: `allowConcurrentBuilds`, `onMaster('1h')`, `notify(FAILURE/UNSTABLE)`, `buildmaster.notifyMergeResult()`
- **Rationale**: Core deploy-chain primitive with clear inputs and outputs.
- **Notes**: Display name includes COMMIT_ID + GIT_REVISIONS + REVISION_DESCRIPTION. Creates GAE version name via `make gae_version_name`. Sender: "Mr Monkey" `:monkey_face:`.

---

### notify-znd-owners — `migrate`

- **Source file**: `notify-znd-owners.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**: none
- **Secrets/auth**: `Slack__API_token_for_alertlib` via GCP Secret Manager; `jenkins-deploy-gcloud-service-account.json`
- **External systems**: GCP (Secret Manager, Cloud Logging), Slack, GitHub (Khan/webapp), Python3 virtualenv
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster()`, `withTimeout('1h')` in both setup and run stages
- **Rationale**: Simple utility job running a single Python script with standard notifications.
- **Notes**: Runs `deploy/notify_znd_owners.py`. Requires Python3 virtualenv setup via `make` targets in webapp/deploy.

---

### qa-metrics — `migrate`

- **Source file**: `qa-metrics.groovy`
- **Trigger type**: manual (⚠ comment says "by schedule and URL trigger" but **no cron is configured** — discrepancy to resolve)
- **Cron schedule**: none
- **Inputs**:
  - `WEBAPP_GIT_REVISION` (string, default: `"master"`)
  - `QA_TOOLS_GIT_REVISION` (string, default: `"master"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; SSH key for `git@github.com:Khan/qa-tools.git` (must be provisioned on worker)
- **External systems**: GCP (SA, gcloud), GitHub (Khan/qa-tools), `ka-test-ec2` worker, Slack (#bot-testing)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onWorker('ka-test-ec2', '6h')`, `notify(FAILURE/UNSTABLE)`
- **Rationale**: Per `plan.md` Phase 6: migrate with minimal behavior change; add TODO to move ownership to `qa-tools` repo.
- **Notes**: TODO: Add cron schedule if required. TODO: Move Slack channel from `#bot-testing` to `#qa`. TODO: Transfer ownership to `qa-tools` repo. Python with `pip install -r requirements.txt` from qa-tools. Temp dir via `mktemp`.

---

### test-buildmaster — `migrate`

- **Source file**: `test-buildmaster.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, **required**, default: `"master"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json` via `GOOGLE_APPLICATION_CREDENTIALS`; `BOTO_CONFIG`; Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/webapp + Khan/internal-services), Slack (#infrastructure-devops), GCP, alertlib
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('90m')`, `withTimeout('60m')` (installDeps), `withTimeout('15m')` (runTests)
- **Rationale**: Self-contained test job with clear dependencies.
- **Notes**: Clones webapp to access GCP secrets for Slack — TODO comment acknowledges this workaround. Sender: "Mr Meta Monkey" `:monkey_face:`. Notifies on SUCCESS, FAILURE, UNSTABLE, ABORTED.

---

### update-devserver-static-images — `migrate`

- **Source file**: `update-devserver-static-images.groovy`
- **Trigger type**: schedule, manual
- **Cron schedule**: `0 22 * * *` (daily 22:00 UTC)
- **Inputs**:
  - `GIT_BRANCH` (string, default: `"automated-commits"`)
  - `SERVICES` (string, default: `""`) — comma-separated; empty = all
  - `MERGE_MASTER` (boolean, default: `true`)
  - `SLACK_CHANNEL` (string, default: `"#infrastructure"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `Slack__API_token_for_alertlib` via GCP Secret Manager
- **External systems**: GCP (GCS/GCR for Docker images), GitHub (Khan/webapp — clone, merge, push to `automated-commits`), Slack (#infrastructure, #local-devserver on failure), Go tooling (`go run ./dev/deploy/cmd/upload-images`)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('10h')`, `retry(5)` on image upload command (known RPC flakiness)
- **Rationale**: Critical daily job with clear dependencies and known failure mode already mitigated by retries.
- **Notes**: Commits to `automated-commits` branch; writes `dev/server/.env.latest_uploaded_build_tag`. Prevents direct pushes to master. RPC error `"Unavailable desc = error reading from server: EOF"` handled by 5 retries.

---

### update-i18n-lite-videos — `migrate`

- **Source file**: `update-i18n-lite-videos.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `H 5 * * 6,2` → UTC equivalent: `0 5 * * 2,6` (Tuesdays + Saturdays at 5:00 AM)
- **Inputs**: none
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `BOTO_CONFIG`; Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GCP (GCS bucket `gs://ka-lite-homepage-data/`), GitHub (Khan/webapp), Slack (#cp-eng)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('23h')`, `withTimeout('1h')` (updateRepo), `withTimeout('22h')` (runAndUpload), email notifications (BACK TO NORMAL, FAILURE, UNSTABLE to `jenkins-admin+builds`)
- **Rationale**: Standard scheduled job; long timeout is intrinsic to the work volume.
- **Notes**: Email notifications to `jenkins-admin+builds` — map to GHA notification step. Requires GCS access to `ka-lite-homepage-data`. `make go_deps` dependency.

---

### update-ownership-data — `migrate`

- **Source file**: `update-ownership-data.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `0 3 * * *` (daily 3:00 AM UTC)
- **Inputs**:
  - `GIT_BRANCH` (string, default: `"automated-commits"`)
  - `SLACK_CHANNEL` (string, default: `"#infrastructure"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `Slack__API_token_for_alertlib` via GCP Secret Manager; `BOTO_CONFIG`
- **External systems**: GitHub (Khan/webapp — merge + push to `automated-commits`), GCS (`gs://webapp-artifacts/ownership_data.json`), Slack (#infrastructure, #infrastructure-platform on failure), Python 3 virtualenv
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('2h')`, parallel watchdog + main for abort detection, `notify(SUCCESS/FAILURE/UNSTABLE/ABORTED)`
- **Rationale**: Standard scheduled maintenance job.
- **Notes**: Runs `dev/tools/update_ownership_data.py`. Uses `safe_git.sh` for atomic pushes. Prevents direct master pushes — must use `automated-commits` branch. Custom Slack emoji: octopus. Tracks consecutive failures.

---

### update-translation-pipeline — `migrate`

- **Source file**: `update-translation-pipeline.groovy`
- **Trigger type**: manual
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`)
  - `ZND_NAME` (string, default: `""`)
  - `REFRESH_CROWDIN_GO` (boolean, default: `true`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `Slack__API_token_for_alertlib` via GCP Secret Manager
- **External systems**: GCP (GKE, `gcr.io/khan-internal-services/crowdin-go`, Cloud Logging, Secret Manager), GitHub (Khan/webapp), Docker (push to `gcr.io/khan-internal-services`), Slack (#cp-eng)
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onWorker('build-worker', '90m')`, `notify(SUCCESS/FAILURE/ABORTED/UNSTABLE)`
- **Rationale**: Clean Makefile-based Docker build+push with GKE deployment refresh; no complex pipeline logic.
- **Notes**: Work performed via `make push` + `make crowdin-go` in `services/content-editing/translation_pipeline`. GKE deployment restart conditional on `REFRESH_CROWDIN_GO`.

---

### webapp-maintenance — `migrate`

- **Source file**: `webapp-maintenance.groovy`
- **Trigger type**: schedule
- **Cron schedule**: `H H * * 0` → UTC equivalent: `0 3 * * 0` (Sundays, arbitrary hour)
- **Inputs**:
  - `JOBS` (string, default: `""`) — comma-separated function names from `weekly-maintenance.sh`; empty = all
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `BOTO_CONFIG`; Slack via `withSecrets.slackAlertlibOnly()`
- **External systems**: GitHub (Khan/webapp — `automated-commits` branch), Slack (#infrastructure), Email (`jenkins-admin+builds, csilvers`), GCP, Python3 virtualenv
- **Jenkins behaviors**: `disableConcurrentBuilds`, `onMaster('10h')`, dynamic stages per maintenance function, `catchError(buildResult: "FAILURE", stageResult: "FAILURE")` per stage (continues on partial failures)
- **Rationale**: Standard scheduled maintenance wrapper; partial failure handling maps cleanly to GHA `continue-on-error`.
- **Notes**: Dynamic stages derived from `jenkins-jobs/weekly-maintenance.sh` function list. Merges from master, runs maintenance tasks, cleans up with `git clean -ffd`. GCP SA and BOTO config must be pre-staged.

---

### webapp-test — `migrate-later`

- **Source file**: `webapp-test.groovy`
- **Trigger type**: manual / chained
- **Cron schedule**: none
- **Inputs**:
  - `GIT_REVISION` (string, default: `"master"`)
  - `BASE_REVISION` (string, default: `"origin/master"`)
  - `SLACK_CHANNEL` (string, default: `"#1s-and-0s-deploys"`), `SLACK_THREAD` (string, default: `""`)
  - `CLEAN` (choice: `some/most/all/none`, default: `"some"`)
  - `NUM_WORKER_MACHINES` (string, default: `onWorker.defaultNumTestWorkerMachines()`)
  - `CLIENTS_PER_WORKER` (string, default: `"2"`)
  - `DEPLOYER_USERNAME`, `REVISION_DESCRIPTION`, `BUILDMASTER_DEPLOY_ID` (strings, default: `""`)
  - `JOB_PRIORITY` (string, default: `"6"`)
- **Secrets/auth**: `jenkins-deploy-gcloud-service-account.json`; `BOTO_CONFIG`; `Slack__API_token_for_alertlib`, `khan_actions_bot_...` via GCP Secret Manager; Cloud Logging credentials
- **External systems**: GitHub (Khan/webapp), GCP (GCE `ka-test-ec2`, Cloud Logging, Secret Manager), Slack, Buildmaster
- **Jenkins behaviors**: `allowConcurrentBuilds`, `resetNumBuildsToKeep(1500)`, `onWorker('ka-test-ec2', '5h')`, `parallel(test-server + test-clients)` with `failFast`, stash `test-info.db` for persistence, custom exceptions (`TestFailed`, `TestsAreDone`)
- **Rationale**: Complex multi-machine distributed test orchestration (test-server + N workers × M clients per worker) with custom Python tooling and HTTP-based synchronization; requires architectural planning for Phase 3.
- **Notes**: Test server communicates with clients via HTTP on port 5001. Workers poll `TEST_SERVER_URL`. Selective test running via `BASE_REVISION` diffing. Stashes test timing DB across builds. Strongly coupled to KA's GCE test infrastructure.

---

## Scope Lock Sign-Off

All 29 `jobs/*.groovy` files have been inventoried. Dispositions are:

- **21 `migrate`**: Ready for phased migration per plan.md phases 3–6.
- **6 `migrate-later`**: Blocked on dependency/complexity concerns noted above; must be revisited before their respective phase begins.
- **2 `skip`**: `make-allcheck` (explicit plan.md directive) and `deploy-webapp_slackmsgs` (not a job).

**Known cross-cutting concerns for all migration PRs:**
1. Replace `withSecrets.slackAlertlibOnly()` + alertlib with direct Slack API steps (existing webapp/frontend patterns).
2. Replace `jenkins-deploy-gcloud-service-account.json` with OIDC + WIF via `google-github-actions/auth`.
3. Map Jenkins `onMaster` / `onWorker` labels to `runs-on: [ephemeral-runner]`; annotate original label in comments.
4. Replace `buildmaster.*` calls with equivalent HTTP steps; add TODO for future buildmaster retirement.
5. Replace Jenkins `lock()` with `concurrency:` blocks (`cancel-in-progress: false` for deploy surfaces).
6. Translate `H` (hash-based Jenkins cron spread) to explicit UTC minute values.
