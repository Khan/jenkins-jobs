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

).addCronSchedule("H 2 * * *"

).apply();


def runScript() {
   withTimeout('6h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // Secrets are currently needed for mark_strings_export
      // Remove this when secrets are no longer needed
      withSecrets() {
          sh("jenkins-jobs/create-all-pot-pickle.sh")
      }
   }
}

// We run on a special worker machine because this job uses so much
// memory and time.
onWorker("ka-content-sync-ec2", "2h") {
   notify([slack: [channel: '#cp-eng',
                   sender: 'I18N Imp',
                   emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                   extraText: "@cp-support",
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
           email: [to: 'jenkins-admin+builds',
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {

      stage("Running script") {
           runScript();
      }
   }
}
