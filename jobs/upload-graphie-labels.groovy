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


def runScript() {
   onMaster('23h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      sh("jenkins-tools/upload-graphie-labels.sh");
   }
}


notify([slack: [channel: '#i18n',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   stage("Building/uploading labels") {
      runScript();
   }
}
