// Clean up queues that are no longer in queue.yaml.

// See webapp's deploy/upload_queues.py's docstring for why this is necessary.

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


def deleteQueues() {
   withTimeout('1h') {  // normally 15m, but sometimes cloning takes forever.
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         sh("make python_deps");
         sh("sudo rm -f /etc/boto.cfg");
         sh("deploy/upload_queues.py clean");
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: "#bot-testing",
                  sender: 'Taskqueue Totoro',
                  emoji: ':totoro:',
                  when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Deleting queues") {
         deleteQueues();
      }
   }
}
