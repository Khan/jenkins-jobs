// We use these user-defined steps from vars/:
//import vars.exec
//import vars.onMaster
//import vars.withSecrets


// True if our status matches one of the statuses in the `when` list.
def _shouldReport(status, when) {
   // We check if our status is one we want to report on.  One special
   // case: if we are asked to report on success, we also report when
   // we are BACK TO NORMAL, since that is a special case of success.
   return status in when || (status == "BACK TO NORMAL" && "SUCCESS" in when);
}


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
   } else if (status == "BACK TO NORMAL") {
      return "is back to normal";
   } else {
      return "has unknown status (oops!)";
   }
}


// Supported options:
// channel (required): what slack channel to send to
// when (required): under what circumstances to send to jenkins; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
// sender: the name to use for the bot message (e.g. "Jenny Jenkins")
// emoji: the emoji to use for the bot (e.g. ":crocodile:")
def sendToSlack(slackOptions, status) {
   onMaster("1m") {
      withSecrets() {     // you need secrets to talk to slack
         def msg = ("${env.JOB_NAME} ${currentBuild.displayName} " +
                    "${_statusText(status)} (<${env.BUILD_URL}|Open>)");
         def severity = (status in ['FAILURE', 'UNSTABLE'] ? 'error' : 'info');
         sh("echo ${exec.shellEscape(msg)} | " +
            "jenkins-tools/alertlib/alert.py " +
            "--slack=${exec.shellEscape(slackOptions.channel)} " +
            // TODO(csilvers): make success green, not gray.
            "--severity=${severity} " +
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
      // If we are success and the previous build was a failure, then
      // we change the status to BACK TO NORMAL.
      if (status == "SUCCESS" && currentBuild.previousBuild &&
          currentBuild.previousBuild.result in ["FAILURE", "UNSTABLE"]) {
         status = "BACK TO NORMAL";
      }

      if (options.slack && _shouldReport(status, options.slack.when)) {
         // Make sure the user hasn't already sent to slack.
         if (!env.SENT_TO_SLACK) {
            sendToSlack(options.slack, status);
         }
      }
      if (options.email && _shouldReport(status, options.email.when)) {
         sendToEmail(options.email, status);
      }
   }
}
