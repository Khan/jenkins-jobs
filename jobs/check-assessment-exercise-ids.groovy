// Loads all the assessment configs from datastore and checks to make sure that
// their ExerciseIDs are valid (i.e., that there are published exercises with
// those IDs).

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;


new Setup(steps).addCronSchedule(
   '0 * * * *' // Run every hour
).apply();


def _setupWebapp() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
   }
}


def runScript() {
   withTimeout('1h') {
      dir("webapp") {
         exec(["go", "run", "services/assessments/cmd/check_assessments_exercise_ids/main.go"]);
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: '#assessments-alerts',
                   sender: 'Assessment Config Anaconda',
                   emoji: ':snake:',
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Initializing webapp") {
         _setupWebapp();
      }
      stage("Running script") {
         runScript();
      }
   }
}
