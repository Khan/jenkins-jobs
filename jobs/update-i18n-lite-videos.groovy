// Pipeline job to
// 1) Download data from youtube from our i18n partner youtube accounts
// 2) Create intl/translations/videos_*.json needed for lite pages

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

).addCronSchedule("H 5 * * 6,2"

).apply();


def updateRepo() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      dir("webapp") {
         sh("make go_deps");
      }
   }
}


def runAndUpload() {
   withTimeout('22h') {
      withSecrets() {
         dir("webapp") {
            sh("mkdir -p ../lite-video-data");
            // Download the current videos from gcs so we can update them.
            exec(["gsutil", "-m", "rsync",
                  "gs://ka-lite-homepage-data/", "../lite-video-data/"]);

            exec(["tools/content/update-i18n-lite-videos.sh", "../lite-video-data"]);

            // Now upload the changes
            exec(["gsutil", "-m", "rsync",
                  "../lite-video-data/", "gs://ka-lite-homepage-data/"]);
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
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
      stage("Updating webapp repo") {
         updateRepo();
      }
      stage("Updating lite videos") {
         runAndUpload();
      }
   }
}
