// Pipeline job for khanalytics tests.
//
// This runs `make lint`, `make flow`, and `make check` on the khanalytics
// repo.
//
// This does not run the integration tests `make allcheck` since that requires
// docker and root access.  TODO(colin): figure out how to run these.

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onMaster

new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """The git commit-hash to run tests at, or a symbolic name referring
    to such a commit-hash.""",
    "master"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#future-of-pipelines"
).apply();

REPOSITORY = "git@github.com:Khan/khanalytics";

// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
GIT_SHA1 = null;

def initializeGlobals() {
    GIT_SHA1 = kaGit.resolveCommitish(REPOSITORY, params.GIT_REVISION);
}

def _setupKhanalytics() {
    // TODO(colin): something in this script is trying to use secrets.py from
    // the webapp dir, so we have to clone this.  Figure out what and why so we
    // don't need to do this.
    kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
    kaGit.safeSyncTo("git@github.com:Khan/khan-linter", "master");
    kaGit.safeSyncTo(REPOSITORY, GIT_SHA1);
    dir("khanalytics") {
        // TODO(colin): enable deps after figuring out python3 +
        // virtualenv.
        // TODO(colin): this should be `make deps`, but can't be because of an
        // implicit dependency of the makefile on docker.
        // sh("pip install -r ./requirements_dev.txt")
        dir("core/monitor/src") {
            // TODO(colin): move this into `make deps`?
            sh("npm install --no-save");
        }
    }
}

def runTests() {
    onMaster('10m') {
        _setupKhanalytics();
        dir("khanalytics") {
            // TODO(colin): this should be `make lint`, but can't be because of
            // an implicit dependency of the makefile on docker.
            sh("python3.6 ../khan-linter/bin/ka-lint --blacklist=yes");
            // TODO(colin): this should make `make flow`, but can't be because
            // of an implicit dependency of the makefile on docker.
            dir("core/monitor/src") {
                sh("./node_modules/.bin/flow check .");
            }
            // TODO(colin): enable tests after figuring out python3 +
            // virtualenv.
            // TODO(colin): this should make `make check`, but can't be because
            // of an implicit dependency of the makefile on docker.
            // sh("./runtests.py");
            // TODO(colin): some kind of summary report?
        };
    }
}

notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED', 'SUCCESS']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
    initializeGlobals();
    runTests();
}
