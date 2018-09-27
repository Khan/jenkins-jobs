// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

def call(timeoutString, Closure body) {
   node("gce_porting") {
      timestamps {
         kaGit.checkoutJenkinsTools();
         withVirtualenv() {
            withTimeout(timeoutString) {
               body();
            }
         }
      }
   }
}
