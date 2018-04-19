// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

// timeout is an outer bound on how long we expect body to take.
// It is like '5s' or '10m' or '20h' or '1d'.
// workspace is the name of a workspace to use, if we don't want to use the
// main one.  This is used by jobs like deploy-znd that tend to build on old
// branches and thus wipe away chached builds.
// TODO(benkraft): This duplicates almost entirely from onTestWorker; refactor
// and simplify.
def call(def timeoutString, def workspace = 'webapp-workspace', Closure body) {
   node("build-worker") {
      timestamps {
         // We use a shared workspace for all jobs that are run on the
         // ec2 build machines.
         dir("/home/ubuntu/${workspace}") {
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
