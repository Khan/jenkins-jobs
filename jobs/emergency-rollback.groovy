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

// No bespoke setup: this is as simple a job as they get!
new Setup(steps).apply();


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
            sh("deploy/rollback.py");
         }
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['BUILD START', 'SUCCESS',
                       'FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "1h"]) {
    stage("setup") {
        doSetup();
    }
    stage("rollback") {
        doRollback();
    }
    // Let's kick off the content-publish e2e tests again to make sure
    // everything is working ok.
    build(job: '../misc/content-publish-e2e-test',
          wait: false,
          propagate: false,  // e2e errors are not fatal for a rollback
          parameters: [
             string(name: 'URL', value: "https://www.khanacademy.org"),
             string(name: 'SLACK_CHANNEL', value: "#1s-and-0s-deploys"),
             booleanParam(name: 'FORCE', value: true),
          ]);
}
