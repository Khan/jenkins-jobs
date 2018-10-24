// Pipeline job that does the following:
// * Creates the all.pot.pickle file by collecting all strings that need
//   translations. (calls create-all-pot-pickle.sh)


@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps
// TODO(Kai): tempoary disable cron schedule
// until figure out the OOM issue of Jenkins server
// ).addCronSchedule("H 2 * * *"

).apply();


def runScript() {
   withTimeout('10h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // Secrets are currently needed for mark_strings_export
      // Remove this when secrets are no longer needed
      withSecrets() {
          sh("jenkins-jobs/create-all-pot-pickle.sh")
      }
   }
}


onWorker("ka-i18n-ec2", "10h")  {
   notify([slack: [channel: "#bot-testing",
                  sender: 'Taskqueue Totoro',
                  emoji: ':totoro:',
                  when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Running script") {
           runScript();
      }
   }
}
