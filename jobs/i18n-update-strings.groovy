// Pipeline job that does the following:
// * Uploads to Crowdin the latest all.pot file from source control.
// * Downloads up-to-date translations for the JIPT language from crowdin.
// * Triggers running the i18n-gcs-upload Jenkins job.

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

).addCronSchedule("H 2 * * *"

).apply();


def runScript() {
   withTimeout('5h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // Remove output from a previous run.  Re-created by
      // update-translations.
      sh("rm -f updated_locales.txt")

      // TODO(csilvers): see if we can break up this script into
      // pieces, so we can put using-a-lot-of-memory only around
      // the parts that use a lot of memory.
      lock("using-a-lot-of-memory") {
         withSecrets() {
            withEnv(["UPDATE_STRINGS=1"]) {
               sh("jenkins-jobs/update-translations.sh")
            }
         }
      }

      return readFile("updated_locales.txt").split("\n").join(" ");
   }
}


def tryUpdateStrings() {
  onMaster('6h') {
     notify([slack: [channel: '#i18n',
                     sender: 'I18N Imp',
                     emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                     extraText: "@joshua",
                     when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
             email: [to: 'jenkins-admin+builds',
                     when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
             aggregator: [initiative: 'infrastructure',
                          when: ['SUCCESS', 'BACK TO NORMAL',
                                 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
        def updatedLocales = '';

        // i18n-download-translations also uses our workspace, and edits files
        // in it.  So we don't want to run at the same time it does.  We use
        // this lock to prevent that.
        lock("using-update-strings-workspace") {
           stage("Running script") {
              updatedLocales = runScript();
           }
        }

        currentBuild.displayName = "${currentBuild.displayName} (${updatedLocales})";

        stage("Uploading to gcs") {
           build(job: 'i18n-gcs-upload',
                 parameters: [
                    string(name: 'LOCALES', value: updatedLocales),
                 ])
        }
     }
  }
}

// This job has been pretty flaky, in part due to degraded Crowdin API service,
// so lets try again on failure.
try {
  tryUpdateStrings();
} catch(e) {
  tryUpdateStrings();
}
