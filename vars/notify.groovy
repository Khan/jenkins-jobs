// Normally you will just use notify() directly (via `call()` below).
// But you may also find it useful to use `notify.fail(msg)`.  This
// will not only fail the build, it will include `msg` in the slack
// and/or email notifications.

// We use these user-defined steps from vars/:
//import vars.buildmaster
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


// Returns shared alertlib requirements, including severity, subject,
// and body text. Individual sendTo functions (slack, bugtracker, email,
// and alerta) can build upon these, as needed.
def _dataForAlertlib(status, extraText) {
   // Potential additions to the subject may include currentBuild.displayName
   // and env.BUILD_URL. These may be added in the individual service's sendTo.
   // Do not add if want subject to stay consistent (e.g. for sending to
   // bugtracker, we don't want to open a new task for each failure)
   def subject = "${env.JOB_NAME} ${_statusText(status, false)}";
   def severity = "info";
   def body = "${subject}: See ${env.BUILD_URL} for full details.\n";
   if (_failed(status)) {
      severity = "error";
      body = ("${subject}: See ${env.BUILD_URL}flowGraphTable/ " +
              "for what failed (look for the red circle(s)).\n")
   }
   if (extraText) {
      body += "\n${extraText}";
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
// thread: the thread to which to send the message (must be in the channel).
//    If unspecified or blank, we don't thread the message.
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
   subject += " ${currentBuild.displayName} (<${env.BUILD_URL}|Open>)";
   def sender = slackOptions.sender ?: 'Janet Jenkins';
   def emoji = slackOptions.emoji ?: ':crocodile:';
   if (status == "UNSTABLE") {
      emoji = slackOptions.emojiOnFailure ?: emoji;
      severity = 'warning';
   } else if (_failed(status)) {
      emoji = slackOptions.emojiOnFailure ?: emoji;
   }

   def channelFlags = ["--slack=${slackOptions.channel}"];
   def extraFlags = ["--chat-sender=${sender}",
                     "--icon-emoji=${emoji}"];

   if (slackOptions.thread) {
      channelFlags += ["--slack-thread=${slackOptions.thread}"];
   }

   _sendToAlertlib(subject, severity, body, extraFlags + channelFlags);
   if (_failed(status)) {
      // Also send all failures to dev-support-log
      def logChannelFlags = ["--slack=CB00L3VFZ"];   // dev-support-log
      _sendToAlertlib(subject, severity, body, extraFlags + logChannelFlags);
   }
}

// Update the Github commit status
// Supported options:
//  - sha: The commit SHA to update the status of.
//  - context: The name of the what the results represent (defaults to Jenkins).
//  - repo: The repo to update the status in (defaults to webapp).
//  - owner: The owner of the repo (defaults to Khan).
def sendToGithub(githubOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   subject += "${currentBuild.displayName}";

   // Everything that isn't a failure or a success is reported as "pending"
   def githubStatus = "pending";

   if (_failed(status)) {
      githubStatus = "failure";
   } else if (_shouldReport(status, ["SUCCESS"])) {
      githubStatus = "success";
   }

   def context = githubOptions.context ?: "Jenkins";

   def flags = ["--github-sha=${githubOptions.sha}",
                "--github-target-url=${env.BUILD_URL}",
                "--github-status=${githubStatus}",
                "--summary=${subject}",
                "--github-context=${context}"];

   if (githubOptions.repo) {
      flags += ["--github-repo=${githubOptions.repo}"];
   }

   if (githubOptions.owner) {
      flags += ["--github-owner=${githubOptions.owner}"];
   }

   _sendToAlertlib(subject, severity, body, flags);
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
// when (required): under what circumstances to send to bugtracker; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
//    (Used in call(), below.)
// project (required): a string saying what project to send the issue to,
//    e.g. "Infrastructure".
// bugTags: a list of tags to add to the issue
// watchers: a commas-delimited string of email addresses of
//    who to add to this issue as a watcher.
// [extraText: if specified, text to add to the issue body.]
def sendToBugtracker(bugtrackerOptions, status, extraText='') {
   def arr = _dataForAlertlib(status, extraText);
   def subject = arr[0];
   def severity = arr[1];
   def body = arr[2];
   def extraFlags = ["--bugtracker=${bugtrackerOptions.project}",
                     "--cc=${bugtrackerOptions.watchers ?: ''}",
                     "--bug-tags=${(bugtrackerOptions.bugTags ?: []).join(',')}"];

   _sendToAlertlib(subject, severity, body, extraFlags);
}


// Supported options:
// sha (required): Closure yielding git-sha being processed.  If this doesn't
//     look like a sha, we don't notify the buildmaster.
// what (required): Which job the status refers to.
def sendToBuildmaster(buildmasterOptions, status) {
   // Buildmaster only knows how to handle testing a single (non-abbreviated)
   // git-sha, not one or multiple branch-names.  If the job refers to
   // branches, exit.
   if (!buildmasterOptions.sha ==~ /[0-9a-fA-F]{40}/) {
      return;
   }
   def buildmasterStatus;
   if (status == 'BUILD START') {
      // This one is a special case!  We are sending the ID instead.
      try {
         buildmaster.notifyId(buildmasterOptions.what, buildmasterOptions.sha);
      } catch (e) {
         echo("Notifying buildmaster failed: ${e.getMessage()}.  Continuing.");
      }
      return;
   } else if (status == 'SUCCESS') {
      buildmasterStatus = "success";
   } else if (status == 'BACK TO NORMAL') {
      buildmasterStatus = "success";
   } else if (status == 'ABORTED') {
      buildmasterStatus = "aborted";
   } else if (status == 'UNSTABLE' &&
              buildmasterOptions.what == 'deploy-webapp') {
      buildmasterStatus = "success";
   } else {
      buildmasterStatus = "failed";
   }

   try {
      buildmaster.notifyStatus(
         buildmasterOptions.what, buildmasterStatus, buildmasterOptions.sha);
   } catch (e) {
      echo("Notifying buildmaster failed: ${e.getMessage()}.  Continuing.");
   }
}


def fail(def msg, def statusToSet="FAILURE") {
   throw new FailedBuild(msg, statusToSet);
}

def emitFailureText(e) {
   currentBuild.result = e.getStatusToSet();
   failureText = e.getMessage();
   echo("Failure message: ${failureText}");
   // Log a message to help us ignore this post-build action when
   // analyzing the logs for errors.
   ansiColor('xterm') {
      echo("\033[1;33m===== JOB FAILED =====\033[0m");
   }
   return failureText;
}


def call(options, Closure body) {
   def abortState = [complete: false, aborted: false];
   def failureText = '';

   currentBuild.result = "SUCCESS";
   try {
      if (options.slack && "BUILD START" in options.slack.when) {
         sendToSlack(options.slack, "BUILD START");
      }
      if (options.buildmaster) {
         sendToBuildmaster(options.buildmaster, "BUILD START");
      }
      if (options.github) {
         sendToGithub(options.github, "BUILD START");
      }

      // We do this `parallel` to catch when the job has been aborted.
      // http://stackoverflow.com/questions/36855066/how-to-query-jenkins-to-determine-if-a-still-building-pipeline-job-has-been-abor
      parallel(
         "_watchdog": {
            try {
               // TODO(benkraft): Re-enable the timestamps block around
               // waitUntil once this issue gets fixed:
               //    https://issues.jenkins-ci.org/browse/JENKINS-57163
               waitUntil({ abortState.complete || abortState.aborted });
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
      failureText = emitFailureText(e);
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
      // This should happen before other sendTo*s since a failure to
      // communicate with buildmaster can fail() the build
      if (options.buildmaster) {
         try {
            sendToBuildmaster(options.buildmaster, currentBuild.result);
         } catch (FailedBuild e) {
            failureText = emitFailureText(e);
         }
      }

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
      if (options.github) {
         sendToGithub(options.github, status, failureText);
      }
      if (options.email && _shouldReport(status, options.email.when)) {
         sendToEmail(options.email, status, failureText);
      }
      if (options.bugtracker && _shouldReport(status, options.bugtracker.when)) {
         sendToBugtracker(options.bugtracker, status, failureText);
      }
   }
}
