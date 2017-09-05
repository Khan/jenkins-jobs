// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

// timeout is an outer bound on how long we expect body to take.
// It is like '5s' or '10m' or '20h' or '1d'.
def call(def timeoutString, Closure body) {
   node("ka-test-ec2") {
      timestamps {
         // We use a shared workspace for all jobs that are run on the
         // ec2 test machines.
         dir("/home/ubuntu/webapp-workspace") {
            kaGit.checkoutJenkinsTools();
            withVirtualenv() {
               withTimeout(timeoutString) {
                  // We document what machine we're running on, to help
                  // with debugging.
                  def instanceId = exec.outputOf(
                     ["curl", "-s",
                      "http://169.254.169.254/latest/meta-data/instance-id"]);
                  def ip = exec.outputOf(
                     ["curl", "-s",
                      "http://169.254.169.254/latest/meta-data/public-ipv4"]);
                  echo("Running on ec2 instance ${instanceId} at ip ${ip}");
                  // TODO(csilvers): figure out how to get the worker
                  // to source the .bashrc like it did before.  Now I
                  // think it's inheriting the PATH from the parent instead.
                  withEnv(["PATH=/usr/local/google_appengine:" +
                           "/home/ubuntu/google-cloud-sdk/bin:" +
                           "${env.HOME}/git-bigfile/bin:" +
                           "${env.PATH}"]) {
                     body();
                  }
               }
            }
         }
      }
   }
}
