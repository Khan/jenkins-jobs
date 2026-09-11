import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import groovy.transform.Field

// How long we wait between polls while a run is in progress, in seconds.
// `gh run watch` polls every 3s, which suits a terminal that redraws in
// place; since we print to a log that only scrolls, we poll less often (and
// only print on change).  The cost is up to this many seconds of latency
// noticing a run has finished, which is nothing next to a run's runtime.
@Field POLL_INTERVAL_SECONDS = 20

// How many consecutive failed polls we tolerate before giving up on a run we
// already dispatched.  The GitHub API blips from time to time, and the run
// itself is generally fine when it does.
@Field MAX_POLL_FAILURES = 5

def _githubApiHeaders(String token) {
    return [[name: "Authorization",
             value: "token ${token}",
             maskValue: true],
            [name: "Accept",
             value: "application/vnd.github+json"]]
}

// The browser URL for a workflow file's run list, e.g.
// https://github.com/Khan/webapp/actions/workflows/webapp-test.yml
// Useful before we know the run ID (and if we never find it).
def workflowUrl(String repo, String workflow) {
    return "https://github.com/${repo}/actions/workflows/${workflow}"
}

// The browser URL for a single workflow run, e.g.
// https://github.com/Khan/webapp/actions/runs/12345678.
// (The GitHub API also returns this as each run's `html_url`; this lets
// callers build the same link from a run ID they already have.)
def runUrl(String repo, String runId) {
    return "https://github.com/${repo}/actions/runs/${runId}"
}

// Dispatch a GitHub Actions workflow and return the run ID (string).
//
// args:
//   repo     - GitHub repo, e.g. "Khan/webapp"
//   workflow - Workflow filename, e.g. "webapp-test.yml"
//   ref      - Branch name to run the workflow on
//   inputs   - Map of string→string workflow inputs (optional)
//   token    - a github token, used to perform the workflow_dispatch
//
// Dispatches the workflow via the GitHub API, tagging the dispatch with a
// unique dispatch_id input, then polls for up to 30s to locate the new run
// and return its ID.
def _dispatch(Map args) {
    // A unique-per-call ID we can use to unambiguously identify our run once
    // it's created. workflow_dispatch doesn't return a run ID synchronously,
    // so we have to poll for it afterward; matching on head_sha/head_branch
    // alone is ambiguous when a concurrent or still-running prior dispatch
    // shares the same ref and SHA (e.g. concurrent merge-branches builds
    // against an unmoved master). The dispatched workflow must template this
    // into its `run-name:` (which GitHub surfaces as `display_title`) for the
    // matching below to work.
    //
    // Built from the job name (without the Jenkins folder path), build
    // number, and a millisecond-precision timestamp so it's also
    // recognizable at a glance in the GitHub Actions run list.
    def dispatchId = ("${env.JOB_NAME.tokenize('/').last()}-${env.BUILD_NUMBER}" +
                       "-${new Date().format('yyyyMMdd-HHmmssSSS')}")
    def inputs = (args.inputs ?: [:]) + [dispatch_id: dispatchId]
    def payload = JsonOutput.toJson([ref: args.ref, inputs: inputs])
    notify.log("GitHub Actions dispatch payload", [
        level: "INFO",
        repo: args.repo,
        workflow: args.workflow,
        // A browsable link to this workflow's run list, so someone reading
        // the log can find the run even before we've resolved its ID.
        workflow_url: workflowUrl(args.repo, args.workflow),
        payload: payload,
    ])

    httpRequest(
        contentType: "APPLICATION_JSON",
        customHeaders: _githubApiHeaders(args.token),
        httpMode: "POST",
        requestBody: payload,
        url: "https://api.github.com/repos/${args.repo}/actions/workflows/${args.workflow}/dispatches")

    // Poll for up to 30s (10 attempts × 3s) to find the new run, scoped to
    // this workflow file and matched by its unique dispatch_id.
    def runId = null
    def htmlUrl = null
    for (def i = 0; i < 10; i++) {
        sleep(3)
        def response = httpRequest(
            customHeaders: _githubApiHeaders(args.token),
            httpMode: "GET",
            url: "https://api.github.com/repos/${args.repo}/actions/workflows/${args.workflow}/runs?event=workflow_dispatch&per_page=10")
        def runs = new JsonSlurperClassic().parseText(response.content)
        for (run in runs.workflow_runs) {
            // display_title is rendered from the workflow's own run-name:
            // template, which is free to prefix it with the workflow name
            // (e.g. "merge-branches <dispatch_id>") -- match on containment
            // rather than exact equality so we don't have to know each
            // workflow's chosen format.
            if (run.display_title?.contains(dispatchId)) {
                runId = run.id.toString()
                // The API hands us the browser URL for the run directly;
                // fall back to constructing it just in case.
                htmlUrl = run.html_url ?: runUrl(args.repo, runId)
                break
            }
        }
        if (runId) break
    }

    if (!runId) {
        error("Timed out waiting for GitHub Actions run ID for " +
              "${args.repo}/${args.workflow} on ref ${args.ref}.  " +
              "Look for a run named ${dispatchId} at " +
              workflowUrl(args.repo, args.workflow))
    }

    // Surface the browsable run URL: the only GitHub URLs we'd otherwise log
    // are api.github.com ones, which aren't useful in a browser.
    notify.log("GitHub Actions run started", [
        level: "INFO",
        repo: args.repo,
        workflow: args.workflow,
        run_id: runId,
        run_url: htmlUrl,
    ])

    // Put the link on the Jenkins build page too, so you don't have to dig
    // through the console output to find the run.  Newline-separated because
    // Jenkins's default (plain-text) markup formatter escapes HTML but does
    // turn newlines into line breaks; we append rather than overwrite in case
    // a single build dispatches more than one workflow.
    def descriptionLine = "GitHub Actions run: ${htmlUrl}"
    currentBuild.description = (currentBuild.description
                                ? "${currentBuild.description}\n${descriptionLine}"
                                : descriptionLine)

    return runId
}

// Fetch a run's own status/conclusion plus the status/conclusion of each of
// its jobs.  Returns the parsed JSON: a map with `status`, `conclusion`, and
// `jobs` (a list of maps with `name`, `status`, and `conclusion`).
//
// We go through `gh` rather than the API directly so we don't have to
// paginate the jobs list ourselves.  Note that gh uses "" rather than null
// for a conclusion that doesn't exist yet, so `?:` works on both.
def _fetchRunState(String repo, String runId) {
    def json = exec.outputOf(["gh", "run", "view", runId, "-R", repo,
                              "--json", "status,conclusion,jobs"])
    return new JsonSlurperClassic().parseText(json)
}

// Render a run's state as a header line plus one line per job.  This is both
// what we print and the value we diff against the previous poll, so it must
// not include anything that changes on its own (elapsed times, say) or we'd
// print every time around the loop.
String _renderRunState(String runId, def state) {
    String rendered = "GitHub Actions run " + runId + ": " + state.status
    if (state.conclusion) {
        rendered += " (" + state.conclusion + ")"
    }
    for (job in (state.jobs ?: [])) {
        // A finished job shows its conclusion (success/failure/...), one
        // still going shows its status (queued/in_progress).
        rendered += "\n  [" + (job.conclusion ?: job.status) + "] " + job.name
    }
    return rendered
}

// The jobs of a completed run that didn't succeed, as "name (conclusion)"
// strings, so a failure message says what broke and not just that something
// did.  Skipped jobs are normal (a matrix leg that wasn't needed), so they
// don't count as failures here.
def _failedJobs(def state) {
    def failed = []
    for (job in (state.jobs ?: [])) {
        if (job.conclusion && job.conclusion != "success"
                && job.conclusion != "skipped") {
            failed += "${job.name} (${job.conclusion})"
        }
    }
    return failed
}

// Wait for a GitHub Actions workflow run to complete.
// Blocks until the run finishes; fails the build if the run fails.
//
// We poll ourselves instead of using `gh run watch` because watch redraws the
// entire run -- every job, every few seconds -- which is what you want in a
// terminal that can overwrite itself, and thousands of near-identical lines
// in a Jenkins console log.  Instead we print the full state only when it
// differs from the last state we printed.
def _wait(String repo, String runId, String githubToken) {
    echo("Waiting on GitHub Actions run ${runUrl(repo, runId)}")
    withEnv(["GITHUB_TOKEN=${githubToken}"]) {
        String lastRendered = null
        def failures = 0
        while (true) {
            def state = null
            try {
                state = _fetchRunState(repo, runId)
            } catch (e) {
                notify.rethrowIfAborted(e)
                failures++
                if (failures >= MAX_POLL_FAILURES) {
                    notify.fail("Gave up polling GitHub Actions run " +
                                runUrl(repo, runId) + " after ${failures} " +
                                "failed attempts:\n\n" + e.getMessage(), e)
                }
                echo("Failed to fetch the state of GitHub Actions run " +
                     "${runId} (attempt ${failures} of " +
                     "${MAX_POLL_FAILURES}), retrying: ${e.getMessage()}")
                sleep(POLL_INTERVAL_SECONDS)
                continue
            }
            failures = 0

            String rendered = _renderRunState(runId, state)
            if (rendered != lastRendered) {
                echo(rendered)
                lastRendered = rendered
            }

            if (state.status == "completed") {
                if (state.conclusion == "success") {
                    return
                }
                def failed = _failedJobs(state)
                notify.fail("GitHub Actions workflow ${state.conclusion}: " +
                            runUrl(repo, runId) +
                            (failed ? "\n\nFailed jobs:\n" + failed.join("\n")
                                    : ""))
            }

            sleep(POLL_INTERVAL_SECONDS)
        }
    }
}

// Dispatch a GitHub Actions workflow and wait for it to complete.
//
// args:
//   repo     - GitHub repo, e.g. "Khan/webapp"
//   workflow - Workflow filename, e.g. "webapp-test.yml"
//   ref      - Branch name to run the workflow on
//   inputs   - Map of string→string workflow inputs (optional)
def call(Map args) {
    dispatchAndWait(args)
}

// Dispatch a GitHub Actions workflow, wait for it to complete, and return the
// run ID.  Use this when the Jenkins job needs to fetch artifacts or other
// metadata after the workflow finishes.  Callers that want to show the run to
// a human (in Slack, say) can turn the run ID into a browser link with
// `runGithubAction.runUrl(repo, runId)`.
def dispatchAndWait(Map args) {
    def token = withSecrets.getGithubActionsToken();
    def runId = _dispatch(args + [token: token])
    _wait(args.repo, runId, token)
    return runId
}
