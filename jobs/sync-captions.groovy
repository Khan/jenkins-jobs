// Pipeline job to download captions from YouTube, then upload them production.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H 3 * * 3,6"
).apply();


def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "jobs-work");
   dir("webapp") {
      // now install the other deps
      sh("make clean_pyc");    // in case some .py files went away
      sh("make python_deps");
   }



   withSecrets() {   // We need sleep-secret to post transcripts to prod
      sh("jenkins-jobs/sync-captions.sh");
   }

}


onMaster('23h') {
   // TODO(joshuan): once this fails less than once a week, move to #cp-eng, tag @cp-support, and remove @joshua
   notify([slack: [channel: '#cp-eng',
                   sender: 'I18N Imp',
                   emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                   extraText: "@cp-support",
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'content-platform',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Syncing captions") {
         runScript();
      }
   }
}
