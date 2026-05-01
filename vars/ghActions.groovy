// Utilities for interacting with GitHub Actions from Jenkins pipelines.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Dispatch a GitHub Actions workflow and return the run ID (string).
//
// args:
//   repo     - GitHub repo, e.g. "Khan/webapp"
//   workflow - Workflow filename, e.g. "webapp-test.yml"
//   ref      - Branch name to run the workflow on
//   headSha  - Resolved SHA for the ref (used to identify the new run)
//   inputs   - Map of string→string workflow inputs (optional)
//
// Dispatches the workflow via the GitHub API, then polls for up to 30s
// to locate the new run and return its ID.
def dispatchGithubActionsWorkflow(Map args) {
    def payload = JsonOutput.toJson([ref: args.ref, inputs: args.inputs ?: [:]])

    withCredentials([string(credentialsId: 'GITHUB_TOKEN', variable: 'GH_TOKEN')]) {
        sh(script: """
            curl -sf -X POST \\
              -H "Authorization: token \${GH_TOKEN}" \\
              -H "Accept: application/vnd.github+json" \\
              "https://api.github.com/repos/${args.repo}/actions/workflows/${args.workflow}/dispatches" \\
              -d '${payload}'
        """)

        // Poll for up to 30s (10 attempts × 3s) to find the new run.
        // Filter by head_sha when available to avoid picking up a concurrent
        // dispatch against the same branch.
        def shaFilter = args.headSha ? "&head_sha=${args.headSha}" : ""
        def runId = null
        for (def i = 0; i < 10; i++) {
            sleep(3)
            def runsOutput = sh(script: """
                curl -sf \\
                  -H "Authorization: token \${GH_TOKEN}" \\
                  -H "Accept: application/vnd.github+json" \\
                  "https://api.github.com/repos/${args.repo}/actions/runs?event=workflow_dispatch&per_page=10${shaFilter}"
            """, returnStdout: true).trim()
            def runs = new JsonSlurper().parseText(runsOutput)
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
}

// Wait for a GitHub Actions workflow run to complete.
// Blocks until the run finishes; fails the build if the run fails.
//
// runId - the workflow run ID (string or long)
def waitForGithubActionsWorkflow(String runId) {
    withCredentials([string(credentialsId: 'GITHUB_TOKEN', variable: 'GH_TOKEN')]) {
        sh("gh run watch ${runId} --exit-status")
    }
}
