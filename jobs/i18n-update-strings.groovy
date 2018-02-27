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
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");

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
  notify([slack: [channel: '#i18n',
                  sender: 'I18N Imp',
                  emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                  extraText: "@joshua",
                  when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
          email: [to: 'jenkins-admin+builds',
                  when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
          aggregator: [initiative: 'infrastructure',
                       when: ['SUCCESS', 'BACK TO NORMAL',
                              'FAILURE', 'ABORTED', 'UNSTABLE']],
          timeout: "6h"]) {
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

     // Jenkins sometimes creates multiple workspaces for the same job.
     // i18n-download-translations will use the mtime of this file to figure out
     // which workspace was used by the most recent successful update job,
     // since it depends on all.pot.pickle. We don't want to use
     // updated_locales.txt for this because several things happen after it has
     // been written, so it could be that the job failed after it was written.
     sh("touch updated_timestamp.txt");
  }
}

// This job has been pretty flaky, in part due to degraded Crowdin API service,
// so lets try again on failure.
try {
  tryUpdateStrings();
} catch(e) {
  tryUpdateStrings();
}
