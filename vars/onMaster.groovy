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
                // withEnv(["BOTO_CONFIG=/var/lib/jenkins/.boto"]) {
                     body();
                // }
            }
         }
      }
   }
}
