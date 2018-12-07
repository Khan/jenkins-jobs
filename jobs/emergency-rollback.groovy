// Job to roll back the live version of the site to the previous version.
// It also marks the current version as bad.
//
// This is the script to run if you've done `sun: finish` and only then
// realize that the current deploy has a problem with it.
//
// Note this job does not interact with the deploy pipeline, and can run
// even while a deploy is going on.
//
// You can run this from slack by saying <code>sun: emergency rollback</code>
// in the <code>1s-and-0s-deploys</code> channel.</p>

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets

// We try to keep minimal options in this job: you don't want to have to
// figure out which options are right when the site is down!
new Setup(steps

).addStringParam(
   "JOB_PRIORITY",
   """The priority of the job to be run (a lower priority means it is run
sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
in the queue accordingly. Should always be set to 1. See
https://jenkins.khanacademy.org/advanced-build-queue/ for more information.""",
   "1"

).addBooleanParam(
   "DRY_RUN",
   """Don't actually run emergency rollback, just say what we would do.  This
is primarily useful for making sure the job has a recent checkout of webapp.
(We run it on a cron job for that purpose.)""",
   false

// NOTE(benkraft): This runs in a cron job started from the buildmaster,
// instead of a jenkins cron job, because jenkins cron jobs can't pass
// parameters and we need to pass DRY_RUN.

).apply();

if (params.DRY_RUN) {
   currentBuild.displayName = "${currentBuild.displayName} **DRY RUN**";
}


// TODO(csilvers): maybe use another workspace to save time syncing?
// But decrypting secrets is a problem then.
def doSetup() {
    withTimeout('30m') {
        kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
        dir("webapp") {
           sh("make clean_pyc");
           sh("make python_deps");
        }
    }
}

def doRollback() {
   withTimeout('30m') {
      withSecrets() {
         dir("webapp") {
            if (params.DRY_RUN) {
               sh("deploy/rollback.py -n");
            } else {
               sh("deploy/rollback.py");
            }
         }
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: '#1s-and-0s-deploys',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['BUILD START', 'SUCCESS',
                          'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
       stage("setup") {
           doSetup();
       }
       stage("rollback") {
           doRollback();
       }
       // Let's kick off the e2e tests again to make sure everything is
       // working ok.
       build(job: '../deploy/e2e-test',
             parameters: [
                string(name: 'SLACK_CHANNEL', value: "#1s-and-0s-deploys"),
             ]);
   }
}
