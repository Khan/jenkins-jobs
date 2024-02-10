// Test snap permissions

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

).apply();

onMaster('2h') {
   sleep(5);   // give the watchdog a chance to notice an abort

   node("master") {
      // start = new Date();
      // notify.logNodeStart("master", timeoutString);
      echo("====> done?")
   }

   // notify([slack: [channel: '#miguel-testing',
   //                 sender: 'Testing Turtle',
   //                 emoji: ':turtle:',
   //                 when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   //    stage("Snap permissions") {
   //      echo("Let's see what happens!")
   //    }
   // }
}
