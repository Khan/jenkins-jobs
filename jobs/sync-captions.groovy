// Pipeline job to download captions from YouTube, then upload them production.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
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
   withTimeout('23h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         // now install the other deps
         sh("make clean_pyc");    // in case some .py files went away
         sh("make python_deps");
      }

      withEnv(["SKIP_TO_STAGE=${params.SKIP_TO_STAGE}"]) {
         withSecrets() {   // We need sleep-secret to post transcripts to prod
            sh("jenkins-tools/sync-captions.sh");
         }
      }
   }
}


notify([slack: [channel: '#i18n',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "23h"]) {
   stage("Syncing captions") {
      runScript();
   }
}
