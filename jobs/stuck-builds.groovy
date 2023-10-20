@Library("kautils")
// Standard classes we use.
import groovy.json.JsonBuilder;
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.clean
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withSecrets
//import vars.withTimeout


new Setup(steps).apply();

public class DeployAborted extends Exception {}


def _simpleAlert(def channel, def msg, def severity) {
   args = ["jenkins-jobs/alertlib/alert.py",
           "--slack=${channel}",
           "--chat-sender=Mr Monkey",
           "--icon-emoji=:crying-nickcage:",
           "--slack-simple-message",
           "--severity=${severity}",
          ];
   withSecrets.slackAlertlibOnly() {     // to talk to slack
      echo("sending slack message")
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}


// This funciton will check if the state of the current job has changed
// but somehow the thread is still running. So we will throw an exception
// to trigger the jobs to abort. Let the caller decide if this exception
// should bubble up the stack or not.
def _maybeAbortJob(oldBuildResult, newBuildResult, reason) {
   // NOTE(miguel): Build status changed, let's check if the job was
   // aborted.  We should not be in an aborted state here but we have a
   // theory that the job was aborted but jenkins did not successfully
   // abort the thread because it was in the middle of uninterruptible
   // work like making an HTTP request. If that's the case we are going
   // to cause the job to abort from here to prevent new deploy-webapp
   // jobs from getting stuck.
   // if (oldBuildResult != newBuildResult) {
      // Because this is a theory that build are getting stuck because the
      // signal to abort the current thread while we are in an uninterruptible
      // cycle, we are not 100% sure what the correct things to check are
      // to make an exact desicion. So we are going to log a message to slack
      // to keep track of these cases.
      _simpleAlert(
         "#miguel-testing",
         """Deploy aborted but thread is still running. We most likely have a 
job that is stuck. ${reason}. prev state ${oldBuildResult}. current state 
${newBuildResult}. ${env.BUILD_URL}. 
See https://khanacademy.atlassian.net/wiki/spaces/INFRA/pages/2470543393/Stuck+jenkins+deploy-webapp+builds 
for details to get information about stuck builds. cc @deploy-support""",
         "error",
      )

      if (newBuildResult == "ABORTED") {
         // Jenkins would throw a hudson.AbortException.  But to make
         // things more clear that the job is aborting itself we will
         // throw a different exception.  This exception will (should)
         // be handle by the caller to ensure we clean things up.
         // TODO(miguel): enable once we are confident this will work.
         // Let's run it without auto aborting to get some ideas so that
         // we don't mistakenly start aborting false positives.
         // throw new AbortDeployJob("Deploy was aborted. " + reason)
      }
   // }
}


onMaster('1h') {
   notify([slack: [channel: '#miguel-testing',
                   sender: 'Mr Meta Monkey', // we are testing Mr. Monkey himself!
                   emoji: ':monkey_face:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
 
      stage("Check if build is stuck") {
         if (!currentBuild.nextBuild) {
            echo("Build are good...")
         }
         echo(currentBuild.nextBuild.getClass().getName())
         echo(currentBuild.previousBuild.getClass().getName())

         prevBuildResult = currentBuild.result
         sleep(1)

         _maybeAbortJob("FAKE_STATE", currentBuild.result, "Testing error slack")
      }
   }
}
