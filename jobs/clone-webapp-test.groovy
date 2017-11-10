// Job that only runs tests over all of webapp when a commit is pushed to
// github. This job achieves this by: 
// 1. We want to pull the branch and merge with master, if possible.
// 2. We want to run all of the webapp tests on the newly merged branch
//    OR just the branch (if it is unmerged)
// We are currently waiting on the Deploy State Store to send us git push
// updates. Till then we will manually trigger this job.

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The name of a branch to test (can't be master).
We will automatically merge these branches into master, and test it.""",
    ""
).apply();


currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");

// The slack channel we will send progress alerts to.
SLACK_CHANNEL = "#make-tests-faster";
// Branch we will be running tests on.
BRANCH_TO_TEST = null;
// The tag we will use to tag the commit being tested. It should have a
// 'test-run' prefix.
GIT_TAG = null;
// This holds the arguments to _alert.  It a groovy struct imported at runtime.
alertMsgs = null;


// Returns random tag for the commit to test.
def _generateRandomTag() {
    // We want to produce a random number with 6 digits.
    def digits = 6;
    // Random object
    Random rand = new Random();
    // Tag with 'test-run-<number between 1 - 10^6>'
    return "test-run-${rand.nextInt(10 ** num)}";
}


def _alert(def slackArgs, def interpolationArgs) {
   def msg = "${slackArgs.text}";

   args = ["jenkins-jobs/alertlib/alert.py",
           "--slack=${SLACK_CHANNEL}",
           "--chat-sender=Mr Monkey",
           "--icon-emoji=:monkey_face:",
           "--severity=${slackArgs.severity}",
          ];

   withSecrets() {     // to talk to slack
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}


def initializeGlobals() {
    alertMsgs = load("${pwd()}/../workspace@script/jobs/deploy-webapp_slackmsgs.groovy");
    BRANCH_TO_TEST = params.GIT_REVISION;
    GIT_TAG = _generateRandomTag();
}


// Merge the current branch being tested with master if possible. Otherwise,
// take no action.
def mergeWithMaster() {
    withTimeout('1h') {
        withEnv(["SLACK_CHANNEL=${SLACK_CHANNEL}"]) {
            kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
            kaGit.safePullInBranch("webapp", BRANCH_TO_TEST);

            try {
                // Try to merge the branch with master.
                kaGit.safeMergeFromMaster("webapp", BRANCH_TO_TEST);
            } catch(e) {
                // Leave a message warning that it was unmergable.
                _alert(alertMsgs.FAILED_MERGE_TO_MASTER);
            }
            
            // Create a tag for the head of the branch and push it to remote.
            exec(["git", "tag", GIT_TAG]);
            exec(["git", "push", "origin", GIT_TAG]);

            dir("webapp") {
                sh("make clean_pyc");
                sh("make python_deps");
            }
        }
    }
}


def runTests() {
   build(job: 'webapp-test',
         parameters: [
            string(name: 'GIT_REVISION', value: GIT_TAG),
            string(name: 'TEST_TYPE', value: "all"),
            string(name: 'MAX_SIZE', value: "medium"),
            booleanParam(name: 'FAILFAST', value: false),
            string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
            booleanParam(name: 'FORCE', value: false),
         ]);
}


notify([slack: [channel: "#make-tests-faster",
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        timeout: "4h"]) {

    initializeGlobals();
    stage("Merging master in branch") {
        mergeWithMaster();
    }

    try {
        stage("Running Tests") {
            runTests();
        }
    } catch (e) {
        echo("FATAL ERROR testing: ${e}");
        throw e;
    }
}
