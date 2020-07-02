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
`webapp-test`. Ignored if this job is triggered by the completion of another 
job.""",
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

).addStringParam(
        "GCLOUD_PROJECT",
        "The location of the logs in gcloud to analyze.",
        "khan-internal-services"

).apply();

def installDeps() {
    withTimeout('1m') {
        def commit = params.ANALYZE_BUILD_GIT_REVISION

        if (!fileExists('analyzebuild/.git')) {
            try {
                initRepo(commit)
            } catch (e) {
                echo("couldn't clone analyzebuild.")
                sh(script: 'ls -la `pwd`/analyzebuild/', 
                    returnStatus: true)
            }
        }

        kaGit.safeSyncToOrigin('git@github.com:Khan/analyzebuild', commit)
    }
}

// TODO(davidbraley): Add repo to analyzebuild repo to setup, or adjust
//  safeSyncToOrigin to initialize new repos if needed.
def initRepo(commit) {
    sh('rm -rf ./analyzebuild/')

    // Set REPOS_ROOT directly, as quickClone defaults to assuming it's on
    // the master node, where the REPOS_ROOT is different. On a worker 
    // node, repos are stored in our run directory.
    def pwd = sh(script: 'pwd', returnStdout: true).trim()
    withEnv(["REPOS_ROOT=$pwd"]) {
        // safeSyncToOrigin won't create the repo for us if it doesn't
        // exist, so we clone it first.
        kaGit.quickClone('git@github.com:Khan/analyzebuild', 
            'analyzebuild', commit)
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
        echo('running Analysis')
        upstream = getUpstreamJobAndBuild()

        def upstreamJobName = upstream["job"]
        def upstreamBuild = upstream["build"]

        echo ("upstream: $upstreamJobName:$upstreamBuild")
        
        if (params.JOB_TO_ANALYZE) {
            echo ("overriding job name: $upstreamJobName -> $params.JOB_TO_ANALYZE")
            upstreamJobName = params.JOB_TO_ANALYZE
        }
        if (params.BUILD_NUMBER) {
            echo ("overriding job name: $upstreamBuild -> $params.BUILD_NUMBER")
            upstreamBuild = params.BUILD_NUMBER
        }
        // def upstreamJobName = params.JOB_TO_ANALYZE
        // def upstreamBuild = params.BUILD_NUMBER
        def gcloudProject = params.GCLOUD_PROJECT

        // def upstream = currentBuild.rawBuild.getCause(
            // hudson.model.Cause$UpstreamCause)

        // if (upstream) {
        //     upstreamJobName = upstream.upstreamProject
        //     upstreamBuild = upstream.upstreamBuild
        // }

        if (!(upstreamBuild && upstreamJobName)) {
            notify.fail("JOB_TO_ANALYZE and BUILD_NUMBER must be set if " +
                "manually triggering this job")
        }

        echo "Analyzing ${upstreamJobName} - ${upstreamBuild}"

        dir("analyzebuild") {
            echo("inside analyzebuild")
            sh("ls -la .")
            sh("ls -la `pwd`/cmd")
            sh("which go")
            sh("go version")
            echo("running: go run ./cmd/analyzebuild.go -trace=gcloud://$gcloudProject -job $upstreamJobName -build $upstreamBuild")
            exec(["go","run","./cmd/analyzebuild.go",
                "-trace=gcloud://$gcloudProject",
                "-job","$upstreamJobName",
                "-build","$upstreamBuild"])
        }
    }
}

// TODO(davidbraley): This should run probably run on a worker rather than 
//  master, but from which pool? Should it get it's own pool of workers?
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
