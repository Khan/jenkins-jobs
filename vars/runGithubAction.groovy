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
//   headSha  - Resolved SHA for the ref (used to identify the new run)
//   inputs   - Map of string→string workflow inputs (optional)
//   token    - a github token, used to perform the workflow_dispatch
//
// Dispatches the workflow via the GitHub API, then polls for up to 30s
// to locate the new run and return its ID.
def _dispatch(Map args) {
    def payload = JsonOutput.toJson([ref: args.ref, inputs: args.inputs ?: [:]])
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

    // Poll for up to 30s (10 attempts × 3s) to find the new run.
    // Filter by head_sha when available to avoid picking up a concurrent
    // dispatch against the same branch.
    def shaFilter = args.headSha ? "&head_sha=${args.headSha}" : ""
    def runId = null
    for (def i = 0; i < 10; i++) {
        sleep(3)
        def response = httpRequest(
            customHeaders: _githubApiHeaders(args.token),
            httpMode: "GET",
            url: "https://api.github.com/repos/${args.repo}/actions/runs?event=workflow_dispatch&per_page=10${shaFilter}")
        def runs = new JsonSlurperClassic().parseText(response.content)
        for (run in runs.workflow_runs) {
            if (run.head_branch == args.ref) {
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
//   headSha  - Resolved SHA for the ref (used to identify the new run)
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
