// Determine what services would be deployed by a webapp commit. Should not
// be started if the deployer has queued with the SERVICES flag because that
// flag would override the result of this job.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.buildmaster
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

new Setup(steps

).allowConcurrentBuilds(

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The sha1 to determine the deployed services for.""",
    ""

).addStringParam(
   "BASE_REVISION",
   """Compute services that would be deployed by the commit since this 
   revision based on the the commits between BASE_REVISION..GIT_REVISION.""",
   ""

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
If not specified, guess from the username of the person who started
this job in Jenkins.  Typically not set manually, but by hubot scripts
such as sun.  You can, but need not, include the leading `@`.""",
   ""

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s-deploys"

).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send failure
alerts.  By default we do not send in a thread.  Generally only set by the
buildmaster, to the 'thread_ts' or 'timestamp' value returned by the Slack
API.""", 
   ""

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.""",
   ""

).addStringParam(
   "BUILDMASTER_DEPLOY_ID",
   """Set by the buildmaster, can be used by scripts to associate jobs
that are part of the same deploy.  Write-only; not used by this script.""",
   ""

).addStringParam(
  "JOB_PRIORITY",
  """The priority of the job to be run (a lower priority means it is run
sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
in the queue accordingly. Should be set to 3 if the job is depended on by
the currently deploying branch, otherwise 6. Legal values are 1
through 11. See https://jenkins.khanacademy.org/advanced-build-queue/
for more information.""",
  "6"

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.REVISION_DESCRIPTION})");

def checkArgs() {
   if (!params.GIT_REVISION) {
      notify.fail('The GIT_REVISION parameter is required.');
   }
}

def checkoutWebapp() {
   deployer_username = notify.getDeployerUsername(params.DEPLOYER_USERNAME)
    
   // TODO(csilvers): have these return an error message instead of alerting 
   // themselves, so we can use notify.fail().
   withEnv(["SLACK_CHANNEL=${params.SLACK_CHANNEL}",
            "SLACK_THREAD=${params.SLACK_THREAD}",
            "DEPLOYER_USERNAME=${deployer_username}",
            "JOB_PRIORITY=${params.JOB_PRIORITY}"]) {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                             params.GIT_REVISION)
   }
}

def determineServicesToDeploy() {
   withVirtualenv.python3() {
      withTimeout('15m') {
         dir('webapp') {
            echo('Determining services that should be deployed.')
            def services = []

            try {
               def shouldDeployArgs = ['deploy/should_deploy.py'];
               // Diff against BASE_REVISION if set.
               if (params.BASE_REVISION) {
                     shouldDeployArgs += [
                        '--from-commit', params.BASE_REVISION]
               }
               services = exec.outputOf(shouldDeployArgs).split('\n');
            } catch(e) {
               notify.fail('Automatic detection of what to deploy ' +
                           'failed. You can likely work around this ' +
                           'by setting services on your deploy; ' +
                           "see ${env.BUILD_URL}rebuild for " +
                           'documentation, and `sun: help flags` ' +
                           "for how to set it. If you aren't sure, " + 
                           'ask deploy-support for help!');
            }

            if (services == [""]) {
               // The above could be [""] if we should deploy nothing. We want 
               // to use [] instead of [""].
               services = [];
            }

            echo("Should deploy services: ${services.join(', ')}");
            return services
         }
      }
   }
}


// We use a build worker, because this is a CPU-heavy job and want to run 
// several at a time.
onWorker('build-worker', '1h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['FAILURE', 'UNSTABLE']],
                   buildmaster: [sha: params.GIT_REVISION,
                                 what: 'determine-webapp-services']]) {
      checkArgs();

      stage('Checkout webapp') {
         checkoutWebapp()
      }

      def services = []
      stage('Determine services') {
         services = determineServicesToDeploy();
      }

      // Phone home the list of services to buildmaster.
      buildmaster.notifyServices(params.GIT_REVISION,
                                 services.join(', ') ?: 'tools-only');
    }
}
