// Pipeline job that does the following:
// * Creates the all.pot.pickle file by collecting all strings that need
//   translations. (calls create-all-pot-pickle.sh)


@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps).addCronSchedule("H 2 * * *").apply();

def runScript() {
   withTimeout('5h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      withSecrets() {
          sh("jenkins-jobs/create-all-pot-pickle.sh")
      }
   }
}