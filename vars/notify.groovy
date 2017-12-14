// Normally you will just use notify() directly (via `call()` below).
// But you may also find it useful to use `notify.fail(msg)`.  This
// will not only fail the build, it will include `msg` in the slack
// and/or email notifications.

// We use these user-defined steps from vars/:
//import vars.exec
//import vars.onMaster
//import vars.withSecrets


// Used to set status to FAILURE and emit the failure reason to slack/email.
class FailedBuild extends Exception {
   def statusToSet;

   FailedBuild(def msg, def statusToSet="FAILURE") {
      super(msg);
      this.statusToSet = statusToSet;
   }
};


// True if our status matches one of the statuses in the `when` list.
def _shouldReport(status, when) {
   // We check if our status is one we want to report on.  One special
   // case: if we are asked to report on success, we also report when
   // we are BACK TO NORMAL, since that is a special case of success.
   return status in when || (status == "BACK TO NORMAL" && "SUCCESS" in when);
}


// True if the status code is one of the ones that indicates failure.
def _failed(status) {
   return status in ['FAILURE', 'UNSTABLE', 'ABORTED', 'NOT_BUILT'];
}


// Number of jobs that have failed in a row, including this one.
def _numConsecutiveFailures(status) {
   def numFailures = 0;
   def build = currentBuild;    // a global provided by jenkins
   while (build && _failed(build.result)) {
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


def _statusText(status, includeNumConsecutiveFailures=true) {
   if (status == "FAILURE") {
      def numFailures = (
         includeNumConsecutiveFailures ? _numConsecutiveFailures(status) : 0);
      if (numFailures > 1) {
         return "failed (${_ordinal(numFailures)} time in a row)";
      }
      return "failed";
   } else if (status == "UNSTABLE") {
      return "is unstable";
   } else if (status == "ABORTED") {
      return "was aborted";
   } else if (status == "NOT_BUILT") {
      return "was not built";
   } else if (status == "SUCCESS") {
      return "succeeded";
   } else if (status == "BUILD START") {
      return "is starting";
   } else if (status == "BACK TO NORMAL") {
      return "is back to normal";
   } else {
      return "has unknown status (oops!)";
   }
}


// Returns the last 50 lines of the logfile.  However, we omit the
// text of commands run by notify() (below) when it's easy to do so,
// since they are run after the job proper has already finished, and
// don't provide any useful information for debugging.
def _logSuffix() {
   def NUM_LINES = 50;
   // We allow 400 lines of post-failure processing.
   // TODO(csilvers): rawBuild is a bit of a security hole; revoke
   // permissions for it and use logMatcher instead if we can get it
   // to work.
   def loglines = currentBuild.rawBuild.getLog(NUM_LINES + 400);

   // We always include loglines[0], that's the one that says
   def end = loglines.size() - 1;
   if (end < 1) {
      return "";
   }
   for (def i = 0; i < loglines.size(); i++) {
      if ("===== JOB FAILED =====" in loglines[i]) {
         end = i;
         break;
      }
   }
   def start = end - NUM_LINES < 1 ? 1 : end - NUM_LINES;
   return loglines[0, start..end].join("\n");
}


// Returns shared alertlib requirements, including severity, subject,
// and body text. Individual sendTo functions (slack, asana, email,
// and alerta) can build upon these, as needed.
def _dataForAlertlib(status, extraText) {
   // Potential additions to the subject may include currentBuild.displayName
   // and env.BUILD_URL. These may be added in the individual service's sendTo.
   // Do not add if want subject to stay consistent (e.g. for sending to Asana,
   // we don't want to open a new task for each failure)
   def subject = "${env.JOB_NAME} ${_statusText(status, false)}";
   def severity = _failed(status) ? 'error' : 'info';
   def body = "${subject}: See ${env.BUILD_URL} for full details.\n";
   if (extraText) {
      body += "\n${extraText}";
   }
   if (_failed(status)) {
      body += """
Below is the tail of the build log.
If there's a failure it is probably near the bottom!

---------------------------------------------------------------------

${_logSuffix()}
""";
   }
   return [subject, severity, body];
}


// Given all necessary arguments, builds shellcommand and sends to alertlib.
def _sendToAlertlib(subject, severity, body, extraFlags) {
   def shellCommand = ("echo ${exec.shellEscape(body)} | " +
                       "jenkins-jobs/alertlib/alert.py " +
                       "--severity=${exec.shellEscape(severity)} " +
                       "--summary=${exec.shellEscape(subject)} " +
                       exec.shellEscapeList(extraFlags));

   // Do our best to make sure alertlib has its deps installed.
   try {
      dir("jenkins-jobs/alertlib") {
         sh("make deps");
      }
   } catch (e) {
      echo("Unable to install dependencies for alertlib, but continuing...");
   }


   // Sometimes we want to notify before webapp has even been
   // cloned, at which point secrets aren't available.  So we just
   // do best-effort.  Presumably this will only happen once, the
   // first time a new jenkins job is ever run, so I don't worry
   // about it too much.  TODO(csilvers): instead, move secrets to
   // some other repo, or clone webapp in withSecrets().
   withSecrets.ifAvailable() {     // you need secrets to talk to slack
      sh(shellCommand);
   }
}


// Supported options:
// channel (required): what slack channel to send to
// when (required): under what circumstances to send to slack; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
//    (Used in call(), below.)
// sender: the name to use for the bot message (e.g. "Jenny Jenkins")
// emoji: the emoji to use for the bot (e.g. ":crocodile:")
// emojiOnFailure: the emoji to use for the bot when sending a message that
//    the job failed.  If not specified, falls back to emoji.
// extraText: if specified, text to add to the message send to slack.
// [extraText: if specified, text to add to the message send to slack.]
// TODO(csilvers): make success green, not gray.
def sendToSlack(slackOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   if (slackOptions.extraText) {
      body += "\n${slackOptions.extraText}";
   }
   subject += "${currentBuild.displayName} (<${env.BUILD_URL}|Open>)";
   def sender = slackOptions.sender ?: 'Janet Jenkins';
   def emoji = slackOptions.emoji ?: ':crocodile:';
   if (status == "UNSTABLE") {
      emoji = slackOptions.emojiOnFailure ?: emoji;
      severity = 'warning';
   } else if (_failed(status)) {
      emoji = slackOptions.emojiOnFailure ?: emoji;
   }

   def extraFlags = ["--slack=${slackOptions.channel}",
                     "--chat-sender=${sender}",
                     "--icon-emoji=${emoji}"];

   _sendToAlertlib(subject, severity, body, extraFlags);
}


// Supported options:
// when (required): under what circumstances to send to email; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
//    (Used in call(), below.)
// to (required): a string saying who to send mail to.  We automatically
//    append "@khanacademy.org" to each email address in the list.
//    If you want to send to multiple people, use a comma: "sal, team".
// cc: a string saying who to cc on the email.  Format is the same as
//    for `to`.
// [extraText: if specified, text to add to the email body.]
def sendToEmail(emailOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   subject += "${currentBuild.displayName}";

   def extraFlags = ["--mail=${emailOptions.to}",
                     "--cc=${emailOptions.cc ?: ''}",
                     "--sender-suffix=${env.JOB_NAME.replace(' ', '_')}"];

   _sendToAlertlib(subject, severity, body, extraFlags);
}


// Supported options:
// when (required): under what circumstances to send to asana; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
//    (Used in call(), below.)
// project (required): a string saying what asana project to send to,
//    e.g. "Engineering support".
// tags: a list of tags to add to the project
// followers: a commas-delimited string of asana email addresses of
//    who to add to this asana task.
// [extraText: if specified, text to add to the task body.]
def sendToAsana(asanaOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   def extraFlags = ["--asana=${asanaOptions.project}",
                     "--cc=${asanaOptions.followers ?: ''}",
                     "--asana-tags=${(asanaOptions.tags ?: []).join(',')}"];

   _sendToAlertlib(subject, severity, body, extraFlags);
}


// Supported options:
// when (required): under what circumstances to send to aggregator; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
//    (Used in call(), below.)
// initiative (required): a string indicating which initiative this pertains,
//    to e.g. "infrastructure"
// [extraText: if specified, text to add to the task body.]
def sendToAggregator(aggregatorOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   subject += "${currentBuild.displayName} See ${env.BUILD_URL} for full details.";
   // 'failed' rather than actual status included so as to provide a consistent
   // event name, otherwise dashboard will not recognize entries as corresponding
   // to the same issue and will open a new issue
   def event_name = "${env.JOB_NAME} failed";

   def extraFlags = ["--aggregator=${aggregatorOptions.initiative}",
                     "--aggregator-resource=jenkins",
                     "--aggregator-event-name=${event_name}"];

   // If job is successful mark alerta alert resolved
   if (!_failed(status)) {
      extraFlags += ["--aggregator-resolve"];
   }
   _sendToAlertlib(subject, severity, body, extraFlags);
}


def fail(def msg, def statusToSet="FAILURE") {
   throw new FailedBuild(msg, statusToSet);
}


def runWithNotification(options, Closure body) {
   def abortState = [complete: false, aborted: false];
   def failureText = '';

   currentBuild.result = "SUCCESS";
   try {
      if (options.slack && "BUILD START" in options.slack.when) {
         sendToSlack(options.slack, "BUILD START");
      }

      // We do this `parallel` to catch when the job has been aborted.
      // http://stackoverflow.com/questions/36855066/how-to-query-jenkins-to-determine-if-a-still-building-pipeline-job-has-been-abor
      parallel(
         "_watchdog": {
            try {
               timestamps {
                  waitUntil({ abortState.complete || abortState.aborted });
               }
            } catch (e) {
               if (!abortState.complete) {
                  abortState.aborted = true;
                  currentBuild.result = "ABORTED";
               }
               throw e;
            } finally {
               abortState.complete = true;
            }
         },
         "main": {
            try {
               body();
            } finally {
               abortState.complete = true;
            }
         },
         "failFast": true,
      );
   } catch (FailedBuild e) {
      currentBuild.result = e.getStatusToSet();
      failureText = e.getMessage();
      echo("Failure message: ${failureText}");
      // Log a message to help us ignore this post-build action when
      // analyzing the logs for errors.
      ansiColor('xterm') {
         echo("\033[1;33m===== JOB FAILED =====\033[0m");
      }
   } catch (e) {
      if (abortState.aborted) {
         currentBuild.result = "ABORTED";
         ansiColor('xterm') {
            echo("\033[1;33m===== JOB ABORTED =====\033[0m");
         }
      } else {
         currentBuild.result = "FAILURE";
         ansiColor('xterm') {
            echo("\033[1;33m===== JOB FAILED =====\033[0m");
         }
      }
      throw e;
   } finally {
      def status = currentBuild.result;

      // If we are success and the previous build was a failure, then
      // we change the status to BACK TO NORMAL.
      if (status == "SUCCESS" && currentBuild.previousBuild &&
          _failed(currentBuild.previousBuild.result)) {
         status = "BACK TO NORMAL";
      }

      if (options.slack && _shouldReport(status, options.slack.when)) {
         // Make sure the user hasn't already sent to slack.
         if (!env.SENT_TO_SLACK) {
            sendToSlack(options.slack, status, failureText);
         }
      }
      if (options.email && _shouldReport(status, options.email.when)) {
         sendToEmail(options.email, status, failureText);
      }
      if (options.asana && _shouldReport(status, options.asana.when)) {
         sendToAsana(options.asana, status, failureText);
      }
      if (options.aggregator && _shouldReport(status, options.aggregator.when)) {
         sendToAggregator(options.aggregator, status, failureText);
      }
   }
}


def call(options, Closure body) {
   // This ensures the entire job runs with an executor.  If you don't
   // want that, call runWithNotification() directly; but then you're
   // responsible for using `onMaster()` when appropriate.
   onMaster(options.timeout) {
      runWithNotification(options, body);
   }
}
