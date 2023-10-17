// Check in with all znd owners (via Slack) to ask if they can delete
// their znd.
//
// 'znd's are non-default deploys of our application to appengine.
// Appengine limits how many deploys we can have (default or no),
// and when we run into that limit one way of making space is to
// ask people who have deployed old znd's to delete them.  This
// jenkins job does this asking.
//
// (We're able to identify who deployed what znd since we ask people
// to make their username part of the znd's name.)

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withSecrets
//import vars.withTimeout


// The simplest setup ever! -- we only want the defaults.
new Setup(steps).apply();


def runScript() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
      withSecrets.slackAlertlibOnly() {  // because we pass --notify_slack
         dir("webapp") {
            sh("make clean_pyc");    // in case some .py files went away
            sh("make python_deps");
            sh("deploy/notify_znd_owners.py --notify_slack");
         }
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: '#1s-and-0s-deploys',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Notifying") {
         runScript();
      }
   }
}
