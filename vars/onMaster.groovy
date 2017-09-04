// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

def call(timeoutString, Closure body) {
   timestamps {
      kaGit.checkoutJenkinsTools();
      withVirtualenv() {
         withTimeout(timeoutString) {
            body();
         }
      }
   }
}
