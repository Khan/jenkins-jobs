// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

// How many test-workers we run in parallel, by default.
// In theory, every job can decide the right number of worker machines
// it needs.  But in practice, there's not much point because of the
// way the lockable-resources jenkins plugin works: it only lets
// you allocate fixed-sized "banks" of worker machines.  (There's
// an open issue to give it more flexibility, at which point having
// this default may make less sense.)  This constant says how big
// each "bank" is.
//
// If you update this, you'll want to update jenkins to match: go to
//    https://jenkins.khanacademy.org/configure
// First, search for "Lockable Resources" and see how many banks
// of test-workers we have (the highest `test-workers-#` you see).
// Then search for "Amazon EC2" and then click on "Advanced".
// Then search for "Instance Cap".  Set it to
//    DEFAULT_NUM_WORKER_MACHINES * |number of test-worker banks|
// You should do that *before* deploying any changes here.
DEFAULT_NUM_WORKER_MACHINES = 10;


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
