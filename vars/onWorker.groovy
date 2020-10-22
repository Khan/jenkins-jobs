// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout
//import vars.withVirtualenv

// How many test-workers we run in parallel, by default.
// This no longer needs to be consistent across jobs, but
// most jobs using the ka-test-ec2 workers use it by default.
def defaultNumTestWorkerMachines() {
   return 20;
}

// label is the label of the node.  It should be one of the worker labels
// defined under "Cloud" in the global jenkins settings, currently:
def validWorkerLabels() {
   return [
      "ka-test-ec2",        // used for deploy: webapp-test, e2e-test, etc
      "ka-firstinqueue-ec2",    // resreved for the current deploy
      "build-worker",       // used for build-webapp
      "znd-worker",         // used for deploy-znd
      "ka-content-sync-ec2",    // used for build-current-sqlite
      "ka-i18n-ec2",            // used for i18n jobs
      "ka-page-weight-monitoring-ec2",    // used for page-weight-test
      "big-test-worker"     // like ka-test-ec2 but for jobs needing more disk
   ];
}
// timeout is an outer bound on how long we expect body to take.
// It is like '5s' or '10m' or '20h' or '1d'.
def call(def label, def timeoutString, Closure body) {
   if (!(label in validWorkerLabels())) {
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
                  def instanceId = sh(
                     script: ("curl -s " +
                              "http://metadata.google.internal/computeMetadata/v1/instance/hostname " +
                              "-H 'Metadata-Flavor: Google' | cut -d. -f1"),
                     returnStdout: true).trim();
                  def ip = exec.outputOf(
                     ["curl", "-s",
                      "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip",
                      "-H", 'Metadata-Flavor: Google']);
                  echo("Running on GCE instance ${instanceId} at ip ${ip}");
                  // TODO(csilvers): figure out how to get the worker
                  // to source the .bashrc like it did before.  Now I
                  // think it's inheriting the PATH from the parent instead.
                  // To export BOTO_CONFIG, for some reason, worker did not
                  // source the .profile or .bashrc anymore.
                  withEnv(["BOTO_CONFIG=/home/ubuntu/.boto",
                           "PATH=/usr/local/google_appengine:" +
                           "/home/ubuntu/google-cloud-sdk/bin:" +
                           "/usr/local/bin/:" +
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
