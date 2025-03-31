// We use these user-defined steps from vars/:
//import vars.kaGit
//import vars.withTimeout

def call(timeoutString, Closure body) {
   node("master") {
      start = new Date();
      // TODO(csilvers): figure out how to get the worker
      // to source the .bashrc like it did before.  Now I
      // think it's inheriting the PATH from the parent instead.
      // To export BOTO_CONFIG, for some reason, worker did not
      // source the .profile or .bashrc anymore.
      withEnv(["BOTO_CONFIG=${env.HOME}/.boto",
               "GOOGLE_APPLICATION_CREDENTIALS=${env.HOME}/jenkins-deploy-gcloud-service-account.json",
               "PATH=" +
               "/home/ubuntu/webapp-workspace/devtools/khan-linter/bin:" +
               "/var/lib/jenkins/repositories/khan-linter/bin" +
               "/usr/local/google_appengine:" +
               "/home/ubuntu/google-cloud-sdk/bin:" +
               "${env.HOME}/go/bin:" +
               "${env.PATH}"]) {
          timestamps {
             notify.logNodeStart("master", timeoutString);
             kaGit.checkoutJenkinsTools();
             withTimeout(timeoutString) {
                 echo("Running on main jenkins-server");
                 body();
             }
             notify.logNodeFinish("master", timeoutString, start);
          }
      }
   }
}
