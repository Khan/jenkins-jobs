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

).addCronSchedule(
   // Run every Tuesday at 10am.  The time is arbitrary, but during business
   // hours so we can fix things if they break.
   '0 10 * * 2'

).apply();


def deleteQueues() {
   withTimeout('15m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         sh("make python_deps");
         sh("deploy/upload_queues.py clean");
      }
   }
}


onMaster('30m') {
   notify([slack: [channel: '#infrastructure',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Deleting queues") {
         deleteQueues();
      }
   }
}
