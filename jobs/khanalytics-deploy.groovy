// Deploy khanalytics to production.
// The deploy consists of three phases:
// 1. Substate khanalytics master into khanalytics-private.
// 2. Run the tests on the substated khanalytics submodule.
// 3. Run the deploy script from khanalytics-private.

@Library("kautils")

import org.khanacademy.Setup;
//import vars.kaGit
//import vars.notify

new Setup(steps).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send status alerts.",
   "#future-of-pipelines"
).apply();

REPOSITORY = "git@github.com:Khan/khanalytics-private";

def runTests() {
    // TODO(colin): allow customizing the commit we're deploying?
    build(job: 'khanalytics-test',
          parameters: [
              string(name: 'GIT_REVISION', value: 'master'),
              string(name: 'SLACK_CHANNEL', value: params.SLACK_CHANNEL),
          ]);
}

def cloneKhanalyticsPrivate() {
    // Unhappily, we need to clone webapp in this workspace so that we have
    // secrets for reporting to slack.
    // TODO(colin): just clone the secrets instead of webapp when we have that
    // ability.
    kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
    // TODO(colin): allow customizing the khanalytics-private commit we're
    // deploying?
    kaGit.safeSyncTo(REPOSITORY, 'master');
}

def substateKhanalytics() {
    kaGit.safeUpdateSubmodulePointerToMaster('khanalytics-private', 'khanalytics');
}

def deploy() {
    dir('khanalytics-private') {
        // TODO(colin): this makefile rule will set a global configuration for
        // what kubernetes cluster to talk to. If we ever have jobs that
        // interact with other clusters, we will need to wrap this in a lock.
        withEnv(["CLOUDSDK_COMPUTE_ZONE=us-central1-c"]) {
            sh("make deploy-prod");
        }
    }
}

notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'BUILD START', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL', 'FAILURE',
                            'UNSTABLE', 'ABORTED']],
        timeout: "30m"]) {

    stage("Cloning repository"){
        cloneKhanalyticsPrivate();
    }

    stage("Updating substate") {
        substateKhanalytics();
    }

    stage("Running tests") {
        runTests();
    }

    stage("Deploy") {
        deploy();
    }
}