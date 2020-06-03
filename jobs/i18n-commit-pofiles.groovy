// Pipeline job that commits pofiles to webapp.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps

).addStringParam(
    "LOCALE",
    """Set this to the name of a locale to process, e.g., \"fr\".""",
    ""

).addStringParam(
    "ARCHIVEID",
    """The archive file to download from GCS.  The file downloaded will be:
       https://console.cloud.google.com/storage/browser/ka_translations_archive/<LOCALE>/<LOCALE>-<ARCHIVEID>.pofiles.tar.gz
    """,
    ""

).apply();


def runScript() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      dir("webapp") {
         sh("make clean_pyc");
         sh("make python_deps");
      }

      def locale = params.LOCALE;
      def archiveid = params.ARCHIVEID;

      currentBuild.displayName = ("${currentBuild.displayName} " +
                                  "(${locale})");

      withSecrets() {
         withEnv(["ARCHIVEID=${archiveid}",
                  "LOCALE=${locale}"]) {
            sh("jenkins-jobs/commit-pofiles.sh")
         }
      }
   }
}


onMaster('3h') {
   notify([slack: [channel: '#cp-eng',
                   sender: 'I18N Imp',
                   emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                   extraText: "@cp-support",
                   when: ['FAILURE', 'UNSTABLE']],
           email: [to: 'content-platform',
                   when: ['FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'content-platform',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Running script") {
         runScript();
      }
   }
}

