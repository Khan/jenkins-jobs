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
   withTimeout('60m') { // webapp can take quite a while
      // Unhappily, we need to clone webapp in this workspace so that we have
      // secrets for reporting to slack.
      // TODO(benkraft): just clone the secrets instead of webapp when we have
      // that ability.
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      kaGit.safeSyncToOrigin("git@github.com:Khan/internal-services", "master");
      // In theory that's all we need; repos that need more deps will install
      // them themselves.
   }
}

def deploy() {
   withTimeout('15m') {
      dir("internal-services/${params.SERVICE}") {
         withEnv([
            // We install pipenv globally (using pip3, which doesn't use the
            // virtualenv), so we need to add this to the path.
            // TODO(benkraft): Do this for every job?  It probably doesn't
            // hurt.
            // We also use the jenkins service-account, rather than
            // prod-deploy, because it has the right permissions.
            "PATH=${env.PATH}:${env.HOME}/.local/bin",
            "CLOUDSDK_CORE_ACCOUNT=526011289882-compute@developer.gserviceaccount.com"]) {
            // This automatically runs any applicable tests before deploying.
            sh("make deploy");
         }
      }
   }
}

onMaster('90m') {
   notify([slack: [channel: '#infrastructure-devops',
                   sender: 'Mr Meta Monkey', // we may be deploying Mr. Monkey himself!
                   emoji: ':monkey_face:',
                   when: ['BUILD START',
                          'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      if (params.SERVICE == "") {
         notify.fail("SERVICE is required!");
      }
      stage("Installing deps") {
         installDeps();
      }
      stage("Deploying") {
         deploy();
      }
   }
}
