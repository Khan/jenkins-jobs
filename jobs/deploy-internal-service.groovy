// Groovy script to deploy internal-services (well, one specific service).  We
// always deploy from origin/master.

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
    "SERVICE",
    """<b>REQUIRED</b>. The service to deploy, such as "buildmaster" or
    "ingress".""",
    ""

).apply();

def installDeps() {
   withTimeout('5m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/internal-services", "master");
      // In theory that's all we need; repos that need more deps will install
      // them themselves.
   }
}

def deploy() {
   withTimeout('15m') {
      dir("internal-services/${params.SERVICE)}") {
         // This automatically runs any applicable tests before deploying.
         sh("make deploy");
      }
   }
}

onMaster('30m') {
   notify([slack: [channel: '#infrastructure-devops',
                   sender: 'Mr Meta Monkey', // we may be deploying Mr. Monkey himself!
                   emoji: ':monkey_face:',
                   when: ['BUILD START',
                          'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Installing deps") {
         installDeps();
      }
      stage("Deploying") {
         deploy();
      }
   }
}
   


