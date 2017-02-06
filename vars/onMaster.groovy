// We use these user-defined steps from vars/:
//import kaGit
//import withTimeout
//import withVirtualenv

def call(timeoutString, Closure body) {
   node("master") {
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
