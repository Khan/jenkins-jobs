// Groovy script to deploy to the AWS lambda service
// from master on https://github.com/Khan/alert-context.
//
// alert-context is a lambda function that intercepts slack messages
// from gcloud and augments them to have more useful information
// before passing them on to the ka slack channels.


@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onMaster


// The easiest setup ever! -- we just use the defaults.
new Setup(steps).apply();


def installDeps() {
   onMaster('15m') {
      kaGit.safeSyncTo("git@github.com:Khan/alert-context", "master");

      // These secrets are installed into the workspace when we run the
      // jenkins setup script (in Khan/aws-config).
      sh("ln -snf ../secrets.py alert-context/src/secrets.py");
      sh("chmod 644 alert-context/src/secrets.py");

      dir("alert-context") {
         sh("make deps");
      }
   }
}


def deploy() {
   onMaster('1h') {
      // This is also installed via setup.sh.
      def AWS_ACCESS_KEY_ID = readFile("aws_access_key").trim();
      def AWS_SECRET_ACCESS_KEY = readFile("aws_secret").trim();

      dir("alert-context") {
         withEnv(["AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}",
                  "AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}"]) {
            sh("make deploy");
         }
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['BUILD START',
                       'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   stage("Installing deps") {
      installDeps();
   }
   stage("Deploying") {
      deploy();
   }
}
