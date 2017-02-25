// We use these user-defined steps from vars/:
//import vars.exec
//import vars.onMaster
//import vars.withSecrets

def sendToSlack(slackOptions, status) {
   onMaster('1m') {
      withSecrets() {     // you need secrets to talk to slack
         if (status == 'FAILURE') {
            status = 'failed';
         } else if (status == 'UNSTABLE') {
            status = 'is unstable';
         } else if (status == 'SUCCESS') {
            status = 'succeeded';
         } else {
            status = 'has unknown status (oops!)';
         }

         def msg = ("${env.JOB_NAME} ${currentBuild.displayName} ${status} " +
                    "(<${env.BUILD_URL}|Open>)";
         sh("echo ${exec.shellEscape(msg)} | " +
            "jenkins-tools/alertlib/alert.py " +
            "--slack=${exec.shellEscape(slackOptions.channel)} " +
            // TODO(csilvers): make success green, not gray.
            "--severity=${status == 'succeeded' ? 'info' : 'error'} " +
            "--chat-sender=${exec.shellEscape(slackOptions.sender ?: 'Janet Jenkins')} " +
            "--icon-emoji=${exec.shellEscape(slackOptions.emoji ?: ':crocodile:')}");
      }
   }
}

def sendToEmail(emailOptions, status) {
   // TODO
}


def call(options, Closure body) {
   def status = 'SUCCESS';
   try {
      body();
   } catch (e) {
      status = 'FAILURE';
      throw e;
   } finally {
      if (currentBuild.result != null) {
         status = currentBuild.result;
      }
      if (options.slack) {
         // Make sure the user hasn't already sent to slack.
         if (!env.SENT_TO_SLACK) {
            sendToSlack(options.slack, status);
         }
      }
      if (options.email) {
         sendToEmail(options.email, status);
      }
   }
}
