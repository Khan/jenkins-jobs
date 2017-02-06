// We use these user-defined steps from vars/:
//import kaGit
//import withVirtualenv

def call(Closure body) {
   node("ka-test-ec2") {
      timestamps {
         // We use a shared workspace for all jobs that are run on the
         // ec2 test machines.
         dir("/home/ubuntu/webapp-workspace") {
            kaGit.checkoutJenkinsTools();
            withVirtualenv() {
               body();
            }
         }
      }
   }
}
