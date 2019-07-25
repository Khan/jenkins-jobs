// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

def call(timeoutString, Closure body) {
   node("master") {
      timestamps {
         kaGit.checkoutJenkinsTools();
         withVirtualenv() {
            withTimeout(timeoutString) {
                // To export BOTO_CONFIG, for some reason, master did not
                // source the .profile or .bashrc anymore.
                // TODO(benkraft): Should this also do the path-munging that
                // onWorker does?
                withEnv(["BOTO_CONFIG=${env.HOME}/.boto"]) {
                     body();
                }
            }
         }
      }
   }
}
