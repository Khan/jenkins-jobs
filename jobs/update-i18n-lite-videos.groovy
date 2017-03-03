// Pipeline job to
// 1) Download data from youtube from our i18n partner youtube accounts
// 2) Create intl/translations/videos_*.json needed for lite pages

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster


new Setup(steps

).addCronSchedule("H 5 * * 6,2"

).apply();


def runScript() {
   onMaster('23h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      sh("jenkins-tools/update-i18n-lite-videos.sh");
   }
}


notify([slack: [channel: '#i18n',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   stage("Updating lite videos") {
      runScript();
   }
}
