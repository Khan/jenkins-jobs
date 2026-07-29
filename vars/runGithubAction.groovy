import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def _githubApiHeaders(String token) {
    return [[name: "Authorization",
             value: "token ${token}",
             maskValue: true],
            [name: "Accept",
             value: "application/vnd.github+json"]]
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
    def dispatchId = "${env.BUILD_TAG}-${UUID.randomUUID().toString()}"
    def inputs = (args.inputs ?: [:]) + [dispatch_id: dispatchId]
    def payload = JsonOutput.toJson([ref: args.ref, inputs: inputs])
    notify.log("GitHub Actions dispatch payload", [
        level: "INFO",
        repo: args.repo,
        workflow: args.workflow,
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
    for (def i = 0; i < 10; i++) {
        sleep(3)
        def response = httpRequest(
            customHeaders: _githubApiHeaders(args.token),
            httpMode: "GET",
            url: "https://api.github.com/repos/${args.repo}/actions/workflows/${args.workflow}/runs?event=workflow_dispatch&per_page=10")
        def runs = new JsonSlurperClassic().parseText(response.content)
        for (run in runs.workflow_runs) {
            if (run.display_title == dispatchId) {
                runId = run.id.toString()
                break
            }
        }
        if (runId) break
    }

    if (!runId) {
        error("Timed out waiting for GitHub Actions run ID for ${args.repo}/${args.workflow} on ref ${args.ref}")
    }
    return runId
}

// Wait for a GitHub Actions workflow run to complete.
// Blocks until the run finishes; fails the build if the run fails.
def _wait(String repo, String runId, String githubToken) {
    withEnv(["GITHUB_TOKEN=${githubToken}"]) {
        try {
            exec(["gh", "run", "watch", runId, "-R", repo, "--exit-status"])
        } catch (e) {
            notify.rethrowIfAborted(e)
            notify.fail("GitHub Actions workflow failed: " +
                        "https://github.com/${repo}/actions/runs/${runId}\n\n" +
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
// metadata after the workflow finishes.
def dispatchAndWait(Map args) {
    def token = withSecrets.getGithubActionsToken();
    def runId = _dispatch(args + [token: token])
    _wait(args.repo, runId, token)
    return runId
}
