// Deploy khanalytics to production.
// The deploy consists of three phases:
// 1. Substate khanalytics master into khanalytics-private.
// 2. Run the tests on the substated khanalytics submodule.
// 3. Run the deploy script from khanalytics-private.

@Library("kautils")

import org.khanacademy.Setup;
//import vars.exec
//import vars.kaGit
//import vars.notify

new Setup(steps).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send status alerts.",
   "#boto-testing"
).addStringParam(
   "JOB_PRIORITY",
   """The priority of the job to be run (a lower priority means it is run
   sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
   in the queue accordingly. Should be set to 6. Legal values are 1
   through 11. See https://jenkins.khanacademy.org/advanced-build-queue/
   for more information.""",
   "6"
).addBooleanParam(
    "TESTS",
    """Whether to run tests on this deploy. You should never turn this off for
    a normal deploy; this is intended only for debugging the deploy process
    itself once tests have already passed.
    TODO(colin): instead of this option, figure out if tests have already
    passed at this commit and don't run them again.""",
    true
).apply();

REPOSITORY = "git@github.com:Khan/khanalytics-private";
PROJECT = "khanalytics-160822-160823";

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
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
    // TODO(colin): allow customizing the khanalytics-private commit we're
    // deploying?
    kaGit.safeSyncToOrigin(REPOSITORY, 'master');
}

def substateKhanalytics() {
    kaGit.safePull("khanalytics-private/khanalytics");
    kaGit.safeUpdateSubmodulePointerToMaster('khanalytics-private', 'khanalytics');
}

def deploy() {
    dir('khanalytics-private') {
        withEnv(["CLOUDSDK_COMPUTE_ZONE=us-central1-c"]) {
            // TODO(colin): these makefile rules will set a global configuration
            // for what kubernetes cluster to talk to. If we ever have jobs that
            // interact with other clusters, we will need to wrap this in a lock.
            sh("make prepare-deploy-prod");
            sh("make base-image");
            sh("make build-dockerfiles");
            def images = exec.outputOf(["ls", "build/Dockerfiles"]).split('\n');
            def imagesMap = [:];
            for (image in images) {
                def localImage = image;
                imagesMap[image] = {
                    sh("deployment/build_single_image.sh $PROJECT $localImage");
                }
            }
            parallel(imagesMap);
            sh("deployment/retag_deploy_images.sh $PROJECT");
            sh("make deploy-static");
            sh("make update-deployments");
        }
    }
}

onMaster('90m') {
   notify([slack: [channel: "#bot-testing",
                  sender: 'Taskqueue Totoro',
                  emoji: ':totoro:',
                  when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {

       stage("Cloning repository"){
           cloneKhanalyticsPrivate();
       }

       stage("Updating substate") {
           substateKhanalytics();
       }

       stage("Running tests") {
            if (params.TESTS) {
                runTests();
            }
       }

       stage("Deploy") {
           deploy();
       }
   }
}
