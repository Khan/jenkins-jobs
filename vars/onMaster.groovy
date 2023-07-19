// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

def call(timeoutString, Closure body) {
   node("master") {
      start = new Date();
      notify.logNodeStart("master", timeoutString);
      timestamps {
         kaGit.checkoutJenkinsTools();
         withVirtualenv() {
            withTimeout(timeoutString) {
                echo("Running on main jenkins-server");
                // TODO(csilvers): figure out how to get the worker
                // to source the .bashrc like it did before.  Now I
                // think it's inheriting the PATH from the parent instead.
                // To export BOTO_CONFIG, for some reason, worker did not
                // source the .profile or .bashrc anymore.
                withEnv(["BOTO_CONFIG=${env.HOME}/.boto",
                         "PATH=/usr/local/google_appengine:" +
                         "/home/ubuntu/google-cloud-sdk/bin:" +
                         "${env.HOME}/git-bigfile/bin:" +
                         "${env.PATH}"]) {
                     body();
                }
            }
         }
      }
      notify.logNodeFinish("master", timeoutString, start);
   }
}
