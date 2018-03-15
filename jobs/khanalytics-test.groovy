// Pipeline job for khanalytics tests.
//
// This runs `make lint`, `make flow`, and `make check` on the khanalytics
// repo.
//
// This does not run the integration tests `make allcheck` since that requires
// docker and root access.  TODO(colin): figure out how to run these.

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

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

REPOSITORY = "git@github.com:Khan/khanalytics-private";

// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
GIT_SHA1 = null;

def initializeGlobals() {
    GIT_SHA1 = kaGit.resolveCommitish(REPOSITORY, params.GIT_REVISION);
}

// This is largely the same as vars/withVirtualenv.groovy, but modified to use
// python3 (and a different name; some other jenkins code still needs the py2 one).
// TODO(colin): consolidate them.
// This must be called from the workspace root.
def _withPy3Venv(Closure body) {
   if (env.VIRTUAL_ENV_3) {    // we're already in a py3 virtualenv
      body();
      return;
   }
   if (!fileExists("env3")) {
      echo("Creating new virtualenv(s)");
      sh("virtualenv --python=python3.6 env3");
   }

   // There's no point in calling activate directly since we are not
   // a shell.  Instead, we just set up the environment the same way
   // activate does.
   echo("Activating virtualenv ${pwd()}/env3");
   withEnv(["VIRTUAL_ENV=${pwd()}/env3",
            "VIRTUAL_ENV_3=${pwd()}/env3",
            "PATH=${pwd()}/env3/bin:${env.PATH}"]) {
      body();
   }
}


def _setupKhanalytics() {
    // TODO(colin): something in this script is trying to use secrets.py from
    // the webapp dir, so we have to clone this.  Figure out what and why so we
    // don't need to do this.
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
    kaGit.safeSyncToOrigin("git@github.com:Khan/khan-linter", "master");
    kaGit.safeSyncToOrigin(REPOSITORY, GIT_SHA1);
    _withPy3Venv() {
        dir("khanalytics-private/khanalytics") {
            sh("pip install -r ./requirements_dev.txt");
            dir("core/monitor/src") {
                // TODO(colin): move this into `make deps`?
                sh("npm install --no-save");
            }
        }
    }
}

def runTests() {
    withTimeout('10m') {
        _setupKhanalytics();
        _withPy3Venv() {
            dir("khanalytics-private/khanalytics") {
                sh("../../khan-linter/bin/ka-lint --blacklist=yes");
                dir("core/monitor/src") {
                    // TODO(colin): this should be `make flow` but that assumes
                    // flow is installed globally. Fix.
                    sh("./node_modules/.bin/flow check .");
                }
                sh("make mypy");
                sh("make check");
            };
        }
    }
}

notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED', 'SUCCESS']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "5h"]) {
    initializeGlobals();
    runTests();
}
