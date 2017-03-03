// Pipeline job that downloads translations from crowdin.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.onMaster


new Setup(steps

).blockBuilds(["builds-using-update-strings-workspace",
               "builds-using-a-lot-of-memory"]

).addCronSchedule("H/5 * * * *"

).addStringParam(
    "LOCALES",
    """Set this to a whitespace-separate list of locales process, e.g.,
\"fr es pt\". By default, the job will automatically select the locales
most in need of an update.""",
    ""

).apply();


def runScript() {
   onMaster('1h') {
      // We run in the i18n-update-strings workspace.  That way we
      // don't need our own copy of webapp.  This matters because
      // these jobs update intl/translations, which is huge.
      // TODO(csilvers): does this matter anymore with git-workdir?
      dir("../../i18n-update-strings/workspace") {
         // Remove output from a previous run.  Re-created by
         // update-translations.
         sh('rm -f updated_locales.txt')

         withEnv(['DOWNLOAD_TRANSLATIONS=1',
                  'NUM_LANGS_TO_DOWNLOAD=1',
                  'OVERRIDE_LANGS=${params.LOCALES}']) {
            sh('jenkins-tools/update-translations.sh')
         }

         return readFile("updated_locales.txt").split("\n").join(" ");
      }
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
