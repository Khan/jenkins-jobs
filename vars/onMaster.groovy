// We use these user-defined steps from vars/:
//import checkoutJenkinsTools
//import withVirtualenv

def call(Closure body) {
   node("master") {
      timestamps {
         checkoutJenkinsTools();
         withVirtualenv() {
            body();
         }
      }
   }
}
