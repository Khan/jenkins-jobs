// Pipeline job that
// 1. Builds the translated graphie labels
// 2. Uploads the translated graphie labels to S3

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

).addCronSchedule("H H(2-4) * * *"

).apply();


def updateRepo() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // We do our work in the 'translations' branch.
      kaGit.safePullInBranch("webapp", "translations");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "translations");

      // We also make sure the intl/translations sub-repo is up to date.
      kaGit.safePull("webapp/intl/translations");

      dir("webapp") {
         sh("make clean_pyc");    // in case some .py files went away
         sh("make python_deps");
      }
   }
}


def buildLabels() {
   withTimeout('16h') {
      dir("webapp") {
         def languages = exec.outputOf(["intl/locale_main.py",
                                        "locales_for_packages",
                                        "--exclude-english"]).split("\n");
         for (def i = 0; i < languages.size(); i++) {
            echo("Translating graphie labels for ${languages[i]}.");
            exec(["build/kake/build_prod_main.py", "i18n_graphie_labels",
                  "--language=${languages[i]}"]);
         }
      }
   }
}


def uploadLabels() {
   withTimeout('6h') {
      withSecrets() {  // We need secrets to talk to S3
         dir("webapp") {
              sh("tools/upload_graphie_labels.py");
          }
      }
   }
}


onMaster('23h') {
   notify([slack: [channel: '#cp-eng',
                   sender: 'I18N Imp',
                   emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                   extraText: "@cp-support",
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
           email: [to: 'jenkins-admin+builds',
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
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
}
