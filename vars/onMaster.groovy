// We use these user-defined steps from vars/:
//import kaGit
//import withVirtualenv

def call(Closure body) {
   node("master") {
      timestamps {
         kaGit.checkoutJenkinsTools();
         withVirtualenv() {
            body();
         }
      }
   }
}
