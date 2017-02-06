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
