// We use these user-defined steps from vars/:
//import onMaster
//import withSecrets

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

         sh("echo '${env.JOB_NAME} ${currentBuild.displayName} ${status} (<${env.BUILD_URL}|Open>)' | " +
            "jenkins-tools/alertlib/alert.py " +
            "--slack='${slackOptions.channel}' " +
            // TODO(csilvers): make success green, not gray.
            "--severity=${status == 'succeeded' ? 'info' : 'error'} " +
            "--chat-sender='${slackOptions.sender ?: 'Janet Jenkins'}' " +
            "--icon-emoji='${slackOptions.emoji ?: ':crocodile:'}'");
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
