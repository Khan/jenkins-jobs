// Groovy script to run buildmaster tests.  These are run as a part of deploy,
// but sometimes it's useful to run them yourself.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

// The easiest setup ever! -- we just use the defaults.
new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The git revision on which to run tests.""",
    "master"

).apply();

def installDeps() {
   withTimeout('5m') {
      kaGit.safeSyncToOrigin(
         "git@github.com:Khan/internal-services", params.GIT_REVISION);
      // In theory that's all we need; running `make check` will install the
      // rest of the deps.
   }
}

def runTests() {
   withTimeout('15m') {
      dir("internal-services/buildmaster/image/buildmaster") {
         sh("make check");
      }
   }
}

onMaster('30m') {
   notify([slack: [channel: '#infrastructure-devops',
                   sender: 'Mr Meta Monkey', // we are testing Mr. Monkey himself!
                   emoji: ':monkey_face:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Installing deps") {
         installDeps();
      }
      stage("Deploying") {
         runTests();
      }
   }
}
   


