// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

// How many test-workers we run in parallel, by default.
// This no longer needs to be consistent across jobs, but
// most jobs using the ka-test-ec2 workers use it by default.
def defaultNumTestWorkerMachines() {
   return 10;
}

// label is the label of the node.  It should be one of the worker labels
// defined under "Cloud" in the global jenkins settings, currently:
def VALID_WORKER_LABELS = [
   'ka-test-ec2',        // normal test workers, used for webapp-test,
                         // e2e-test, and other similar jobs
   "build-worker",       // used for build-webapp
   "znd-worker",         // used for deploy-znd
   "ka-content-sync-ec2",              // used for build-current-sqlite
   "ka-page-weight-monitoring-ec2",    // used for page-weight-test
];
// timeout is an outer bound on how long we expect body to take.
// It is like '5s' or '10m' or '20h' or '1d'.
def call(def label, def timeoutString, Closure body) {
   if (!(label in VALID_WORKER_LABELS)) {
      // Fail loudly -- the default behavior is to wait forever.
      // Notify may not notify here -- we're outside a node -- but it'll at
      // least raise an exception, which is the best we can do.
      notify.fail("Worker label ${label} not valid. If you think it " +
                  "should be, add it to vars/onWorker.groovy.");
   }

   node(label) {
      timestamps {
         // We use a shared workspace for all jobs that are run on the
         // worker machines.
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
