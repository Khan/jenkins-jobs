import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

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

// Wait for a GitHub Actions workflow run to complete.
// Blocks until the run finishes; fails the build if the run fails.
def _wait(String repo, String runId, String githubToken) {
    echo("Waiting on GitHub Actions run ${runUrl(repo, runId)}")
    withEnv(["GITHUB_TOKEN=${githubToken}"]) {
        try {
            exec(["gh", "run", "watch", runId, "-R", repo, "--exit-status"])
        } catch (e) {
            notify.rethrowIfAborted(e)
            notify.fail("GitHub Actions workflow failed: " +
                        runUrl(repo, runId) + "\n\n" +
                        e.getMessage(), e)
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
