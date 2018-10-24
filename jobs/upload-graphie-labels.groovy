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
// TODO(Kai): tempoary disable cron schedule
// until figure out the OOM issue of Jenkins server
// ).addCronSchedule("H H(2-4) * * *"

).apply();


def updateRepo() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // We do our work in the 'automated-commits' branch.
      kaGit.safePullInBranch("webapp", "automated-commits");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "automated-commits");

      // We also make sure the intl/translations sub-repo is up to date.
      kaGit.safePull("webapp/intl/translations");

      dir("webapp") {
         sh("make clean_pyc");    // in case some .py files went away
         sh("make python_deps");
         sh("sudo rm -f /etc/boto.cfg");
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

// We run on a special worker machine because this job uses so much
// memory and time.
onWorker("ka-content-sync-ec2", "23h") {
   notify([slack: [channel: "#bot-testing",
                  sender: 'Taskqueue Totoro',
                  emoji: ':totoro:',
                  when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
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

