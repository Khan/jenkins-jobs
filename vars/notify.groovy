// We use these user-defined steps from vars/:
//import vars.exec
//import vars.onMaster
//import vars.withSecrets

// Number of jobs that have failed in a row, including this one.
def _numConsecutiveFailures() {
   def numFailures = 0;
   def build = currentBuild;    // a global provided by jenkins
   while (build && build.result == "FAILURE") {
      numFailures++;
      build = build.previousBuild;
   }
   return numFailures;
}

def _ordinal(num) {
   if (num % 100 == 11 || num % 100 == 12 || num % 100 == 13) {
      return num.toString() + "th";
   }
   def suffix = ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"];
   return num.toString() + suffix[num % 10];
}


def _statusText(status) {
   if (status == "FAILURE") {
      def numFailures = _numConsecutiveFailures();
      if (numFailures > 1) {
         return "failed (${_ordinal(numFailures)} time in a row)";
      }
      return "failed";
   } else if (status == "UNSTABLE") {
      return "is unstable";
   } else if (status == "SUCCESS") {
      return "succeeded";
   } else {
      return "has unknown status (oops!)";
   }
}


def sendToSlack(slackOptions, status) {
   if (!(status in slackOptions.when)) {
      return;
   }
   onMaster("1m") {
      withSecrets() {     // you need secrets to talk to slack
         def msg = ("${env.JOB_NAME} ${currentBuild.displayName} " +
                    "${_statusText(status)} (<${env.BUILD_URL}|Open>)");
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
   def status = "SUCCESS";
   try {
      body();
   } catch (e) {
      status = "FAILURE";
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
