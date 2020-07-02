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
        "PROJECT",
        """Set this to the project you wish to analyze. Ignored if this job is
triggered by the completion of another job.""",
        ""

).addStringParam(
        "BUILD_NUMBER",
        """Set this to the build number you wish to analyze. Ignored if this
job is triggered by the completion of another job.""",
        ""

).addStringParam(
        "SLACK_CHANNEL",
        "The slack channel to which to send failure alerts.",
        "@DavidBraley" // TODO(davidbraley): #infrastructure-devops

).addStringParam(
        "ANALYZE_BUILD_GIT_REVISION",
        "The branch/tag/commit of git@github.com:Khan/analyzebuild to use.",
        "INFRA-4945" // TODO(davidbraley): master

).apply();

def installDeps() {
    withTimeout('1m') {
        def commit = params.ANALYZE_BUILD_GIT_REVISION

        // TODO(davidbraley): Add repo to analyzebuild repo to setup, or adjust
        //   safeSyncToOrigin to initialize new repos if needed.
        // safeSyncToOrigin won't create the repo for us if it doesn't exist,
        // so we clone it first.
        kaGit.quickClone('git@github.com:Khan/analyzebuild', 
            'analyzebuild', commit)

        kaGit.safeSyncToOrigin('git@github.com:Khan/analyzebuild', commit)
    }
}

def runAnalysis() {
    withTimeout('5m') {
        def upstreamProject = params.PROJECT
        def upstreamBuild = params.BUILD_NUMBER

        def upstream = currentBuild.rawBuild.getCause(
            hudson.model.Cause$UpstreamCause)

        if (upstream) {
            upstreamProject = upstream.upstreamProject
            upstreamBuild = upstream.upstreamBuild
        }

        if (!(upstreamBuild && upstreamProject)) {
            notify.fail("PROJECT and BUILD_NUMBER must be set if " + 
                "manually triggering this job")
        }

        echo "Analyzing ${upstreamProject} - ${upstreamBuild}"

        dir('analyzebuild') {
            sh("go run ./cmd/analyzebuild.go " +
                "-trace=gcloud://$upstreamProject " +
                "-job webapp-test " +
                "-build $upstreamBuild")
        }
    }
}

// TODO(davidbraley): This should run probably run on a worker rather than 
//  master, but from which pool? Should it get it's own pool of workers?
onWorker('big-test-worker','15m') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
 
        stage("Sync Analysis Repo") {
            installDeps()
        }

        stage("Running Analysis") {
            runAnalysis()
        }
 
        // Not sure if we need a follow up stage here
    }
}
