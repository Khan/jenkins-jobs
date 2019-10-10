// Update the production postgres db with the most recent migrations from
// master

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.onMaster
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


// The simplest setup ever! -- we only want the defaults.
new Setup(steps).apply();


def runUpdate() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
      withSecrets() {      // we need postgres db secrets
         dir("webapp") {
            sh("make deps");
         }
         dir("webapp/coaches/reports") {
            sh("alembic --name prod upgrade head")
         }
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: '#khan-district-eng',
                   sender: 'Migration Mouse',
                   emoji: ':mouse:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Running update") {
         runUpdate();
      }
   }
}
