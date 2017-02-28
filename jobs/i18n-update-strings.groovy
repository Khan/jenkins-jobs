// Pipeline job that does the following:
// * Uploads to Crowdin the latest all.pot file from source control.
// * Downloads up-to-date translations for the JIPT language from crowdin.
// * Builds the .po files for fake languages, e.g., accents, boxes.
// * Triggers running the i18n-gcs-upload Jenkins job.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.withSecrets


new Setup(steps

).blockBuilds(["builds-using-update-strings-workspace",
               "builds-using-a-lot-of-memory"]

).addCronSchedule("H 2 * * *"

).apply();


def runScript() {
   onMaster('7h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");

      // Remove output from a previous run.  Re-created by
      // update-translations and this script.
      sh('rm -f updated_locales.txt')

      withEnv(['UPDATE_STRINGS=1']) {
         sh('jenkins-tools/update-translations.sh')
      }

      return readFile("updated_locales.txt").split("\n").join(" ");
   }
}


notify([slack: [channel: '#i18n',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   def updatedLocales = '';

   stage("Running script") {
      updatedLocales = runScript();
   }

   currentBuild.displayName = "${currentBuild.displayName} (${updatedLocales})";

   stage("Uploading to gcs") {
      build(job: 'i18n-gcs-upload',
            parameters: [
               string(name: 'LOCALES', value: updatedLocales),
            ])
   }
}
