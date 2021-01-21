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

).addStringParam(
    "SKIP_TO_STAGE",
    """Skip some stages of the sync.
<ul>
  <li>Stage 0 = Download from YouTube.</li>
  <li>Stage 1 = Upload to production.</li>
</ul>""",
    ""

).apply();


def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "thai-up");
   dir("webapp") {
      // now install the other deps
      sh("make clean_pyc");    // in case some .py files went away
      sh("make python_deps");
   }

   withEnv(["SKIP_TO_STAGE=${params.SKIP_TO_STAGE}"]) {
      withSecrets() {
         sh("jenkins-jobs/sync-captions.sh");
      }
   }
}


onMaster('23h') {
   // TODO(joshuan): once this fails less than once a week, move to #cp-eng, tag @cp-support, and remove @joshua
   notify([slack: [channel: '#i18n',
                   sender: 'I18N Imp',
                   emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                   extraText: "@joshua",
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           email: [to: 'jenkins-admin+builds',
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
      stage("Syncing captions") {
         runScript();
      }
   }
}
