// Pipeline job that
// 1. Builds the translated graphie labels
// 2. Uploads the translated graphie labels to S3

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster


new Setup(steps

).addCronSchedule("H H(2-4) * * *"

).apply();


def updateRepo() {
   onMaster('1h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");

      // We do our work in the 'translations' branch.
      kaGit.safePullInBranch("webapp", "translations");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "translations");

      // We also make sure the intl/translations sub-repo is up to date.
      kaGit.safePull("webapp/intl/translations");

      dir("webapp") {
         sh("make python_deps");
      }
   }
}


def buildLabels() {
   onMaster('16h') {
      dir("webapp") {
         def languages = exec.outputOf(["intl/locale_main.py",
                                        "locales_for_packages",
                                        "--exclude-english"]).split("\n");
         for (def i = 0; i < languages.size(); i++) {
            echo("Translating graphie labels for ${languages[i]}.");
            exec(["kake/build_prod_main.py", "i18n_graphie_labels",
                  "--language=${languages[i]}"]);
         }
      }
   }
}


def uploadLabels() {
   onMaster('6h') {
      dir("webapp") {
          withSecrets() {  // We need secrets to talk to S3
              sh("tools/upload_graphie_labels.py");
          }
      }
   }
}


notify([slack: [channel: '#i18n',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   stage("Updating webapp repo") {
      updateRepo();
   }
   stage("Building labels") {
      buildLabels();
   }
   stage("Uploading labels") {
      uploadLabels();
   }
}
