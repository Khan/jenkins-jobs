// Pipeline job that downloads translations from crowdin.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.onMaster


new Setup(steps

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
         dir("webapp") {
            sh("make python_deps");
         }

         // Remove output from a previous run.  Re-created by
         // update-translations.
         sh("rm -f updated_locales.txt")

         def overrideLangs = params.LOCALES;

         if (!overrideLangs) {
            withSecrets() {   // secrets are needed to talk to crowdin
               dir("webapp") { 
                  // If not passed in as a param, get the single highest 
                  // priority lang
                  overrideLangs = exec.outputOf([
                    "deploy/order_download_i18n.py", 
                    "--verbose"]).split("\n")[0];
               } 
            }
         }

         currentBuild.displayName = ("${currentBuild.displayName} " +
                                     "(${overrideLangs})");

         // TODO(csilvers): see if we can break up this script into
         // pieces, so we can put using-a-lot-of-memory only around
         // the parts that use a lot of memory.
         lock("using-a-lot-of-memory") {
            withEnv(["DOWNLOAD_TRANSLATIONS=1",
                     "NUM_LANGS_TO_DOWNLOAD=1",
                     "OVERRIDE_LANGS=${overrideLangs}"]) {
               sh("jenkins-tools/update-translations.sh")
            }
         }

         return readFile("updated_locales.txt").split("\n").join(" ");
      }
   }
}


notify([slack: [channel: '#i18n',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   def updatedLocales = '';

   // We modify files in this workspace -- which is not our own! -- so
   // we acquire a lock to make sure update-strings doesn't try to run
   // at the same time.
   lock("using-update-strings-workspace") {
      stage("Running script") {
         updatedLocales = runScript();
      }
   }

   currentBuild.displayName = "${currentBuild.displayName} (${updatedLocales})";

   // It's possible that no locale was updated. Only trigger the
   // upload job when there are changes.
   if (updatedLocales) {
      stage("Uploading to gcs") {
         build(job: 'i18n-gcs-upload',
               parameters: [
                  string(name: 'LOCALES', value: updatedLocales),
               ])
      }
   }
}
