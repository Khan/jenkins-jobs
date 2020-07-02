// Runs the analysis of a jenkins pipeline

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker
//import vars.withTimeout

new Setup(steps

).addStringParam(
        "JOB_TO_ANALYZE",
        """Set this to the project you wish to analyze. For example, 
`webapp-test`. Not required if this job is triggered by the completion of 
another job.""",
        ""

).addStringParam(
        "BUILD_NUMBER",
        """Set this to the build number you wish to analyze. Not required if 
this job is triggered by the completion of another job.""",
        ""

).addStringParam(
        "SLACK_CHANNEL",
        "The slack channel to which to send failure alerts.",
        "@DavidBraley" // TODO(davidbraley): #infrastructure-devops

).addStringParam(
        "ANALYZE_BUILD_GIT_REVISION",
        "The branch/tag/commit of git@github.com:Khan/analyzebuild to use.",
        "INFRA-4945" // TODO(davidbraley): master

).addStringParam(
        "GCLOUD_PROJECT",
        "The project of the logs in gcloud to analyze.",
        "khan-internal-services"

).apply();

def installDeps() {
    withTimeout('1m') {
        def commit = params.ANALYZE_BUILD_GIT_REVISION

        // REPOS_ROOT needs to be set because the default directory doesn't
        // exist on workers, and will get used in the event the repo has never
        // been synced for this worker. It also cannot be the same as the
        // WORKSPACE_ROOT, which is by default `pwd`.
        withEnv(["REPOS_ROOT=$HOME"]) {
            kaGit.safeSyncToOrigin('git@github.com:Khan/analyzebuild', commit)
        }
    }
}

@NonCPS
def getUpstreamJobAndBuild() {
    def retval = [:]
    def upstream = currentBuild.rawBuild.getCause(
        hudson.model.Cause$UpstreamCause)

    if (upstream) {
        retval["job"] = upstream.upstreamProject
        retval["build"] = upstream.upstreamBuild
    }
    return retval
}

def runAnalysis() {
    withTimeout('5m') {
        upstream = getUpstreamJobAndBuild()

        def upstreamJobName = params.JOB_TO_ANALYZE ?: upstream["job"]
        def upstreamBuild = params.BUILD_NUMBER ?: upstream["build"]
        def gcloudProject = params.GCLOUD_PROJECT

        if (!(upstreamBuild && upstreamJobName)) {
            notify.fail("JOB_TO_ANALYZE and BUILD_NUMBER must be set if " +
                "manually triggering this job")
        }

        dir("analyzebuild") {
            withCredentials([usernamePassword(
                    credentialsId: 'analyze-build-jenkins-auth', 
                    passwordVariable: 'JENKINS_APIKEY', 
                    usernameVariable: 'JENKINS_USER')]) {
                
                exec(["go", "run", "./cmd/analyzebuild.go",
                    "-trace=gcloud://$gcloudProject",
                    "-job", "$upstreamJobName",
                    "-build", "$upstreamBuild"])
            }
        }
    }
}

onWorker('big-test-worker','15m') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   when: ['FAILURE', 'ABORTED', 'UNSTABLE']]]) {

        stage("Sync Analysis Repo") {
            installDeps()
        }

        stage("Running Analysis") {
            runAnalysis()
        }
 
        // Not sure if we need a follow up stage here
    }
}
