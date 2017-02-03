// TODO(csilvers): extract out shared code with onMaster, if that's possible.

def call(Closure body) {
   node("ka-test-ec2") {
      timestamps {
         // We use a shared workspace for all jobs that are run on the
         // ec2 test machines.
         dir("/home/ubuntu/webapp-workspace") {
             // TODO(csilvers): only do this once per workspace per script.
             dir("jenkins-tools") {
                git(url: "https://github.com/Khan/jenkins-tools",
                    changelog: false, poll: false);
                // The built-in 'git' step is not great: it doesn't do
                // submodules, and it messes up the tracking with the remote.
                sh("git submodule update --init --recursive");
                sh("git branch --set-upstream-to origin/master master");
             }

             // Now set up the virtualenv
             sh("./jenkins-tools/build.lib ensure_virtualenv");
             def virtualenvDir = sh(
                script: "cd env && pwd", returnStdout: true).trim();

             withEnv(["VIRTUAL_ENV=${virtualenvDir}",
                      "PATH=${virtualenvDir}/bin:${env.PATH}"]) {
                body();
             }
          }
      }
   }
}
