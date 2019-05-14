// Utility module for interfacing with the buildmaster
import groovy.json.JsonBuilder;
import groovy.transform.Field;

// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.notify
//import vars.retry


@Field BUILDMASTER_TOKEN = null;
@Field SEND_SLACK_COUNT = 0;
@Field MAX_SLACK_MSGS = 3;

alertMsgs = null;

@NonCPS     // for replaceAll()
def _interpolateString(def s, def interpolationArgs) {
   // Arguments to replaceAll().  `all` is the entire regexp match,
   // `keyword` is the part that matches our one parenthetical group.
   def interpolate = { all, keyword -> interpolationArgs[keyword]; };
   def interpolationPattern = "%\\(([^)]*)\\)s";
   return s.replaceAll(interpolationPattern, interpolate);
}

def _sendSimpleInterpolatedMessage(def rawMsg, def interpolationArgs) {
   def SLACK_CHANNEL = "#infrastructure-devops";
   def CHAT_SENDER = 'Mr Monkey';
   def EMOJI = ':monkey_face:';

   def msg = _interpolateString(
      "${rawMsg}", interpolationArgs);

   // ping "#infrastructure-devops" channel when buildmaster is down
   def args = ["jenkins-jobs/alertlib/alert.py",
               "--slack=${SLACK_CHANNEL}",
               "--chat-sender=${CHAT_SENDER}",
               "--icon-emoji=${EMOJI}",
               "--slack-simple-message"];

   // Secrets required to talk to slack.
   withSecrets() {
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}

def initializeBuildmasterToken() {
   if (!BUILDMASTER_TOKEN) {
      BUILDMASTER_TOKEN = readFile(
         "${env.HOME}/buildmaster-api-token.secret").trim();
   }
}

// Make an API request to the buildmaster
// `params` is expected to be a map
def _makeHttpRequestAndAlert(resource, httpMode, params) {
   initializeBuildmasterToken();
   try {
      // We retry if the buildmaster fails.
      // TODO(benkraft): Skip retries on 4xx responses (e.g. invalid commit).
      retry {
         def response = httpRequest(
            acceptType: "APPLICATION_JSON",
            contentType: "APPLICATION_JSON",
            customHeaders: [[name: 'X-Buildmaster-Token',
                             value: BUILDMASTER_TOKEN,
                             // Replace value with ***** when logging request.
                             maskValue: true]],
            httpMode: httpMode,
            requestBody: new JsonBuilder(params).toString(),
            url: "https://buildmaster.khanacademy.org/${resource}");
         SEND_SLACK_COUNT = 0;
         return response;
      }
   } catch (e) {
      // Ideally, we'd just catch hudson.AbortException, but for some reason
      // it's not being caught properly.
      // httpRequest throws exceptions when buildmaster responds with status
      // code >=400

      // checkout jenkins-jobs folder in current workspace
      // in order to load other groovy file.
      kaGit.checkoutJenkinsTools();
      // If the buildmaster is down, we will alert loudly to
      // #infrastructure-devops channel, but don't want to send too much noise.
      if (SEND_SLACK_COUNT < MAX_SLACK_MSGS) {
         alertMsgs = load("${pwd()}/jenkins-jobs/jobs/deploy-webapp_slackmsgs.groovy");
         SEND_SLACK_COUNT += 1;

         echo("Got ${response.getStatus()}, perhaps buildmaster is down.");
         _sendSimpleInterpolatedMessage(
            alertMsgs.BUILDMASTER_OUTAGE,
            [step: "${resource} + ${httpMode}",
            logsUrl: env.BUILD_URL]);
      }
      return;
   }
}

def notifyStatus(job, result, sha1) {
   def params = [
      git_sha: sha1,
      job: job,
      result: result,
      id: env.BUILD_NUMBER as Integer,
   ];
   return _makeHttpRequestAndAlert("commits", "PATCH", params);
}

def notifyMergeResult(commitId, result, sha1, gae_version_name) {
   echo("Marking commit #${commitId} as ${result}: ${sha1}");
   def params = [
      commit_id: commitId,
      result: result,
      git_sha: sha1,
      gae_version_name: gae_version_name
   ];
   return _makeHttpRequestAndAlert("commits/merge", "PATCH", params);
}

def notifyId(job, sha1) {
   echo("Phoning home to log job ID #${env.BUILD_NUMBER} for ${sha1} ${job}");
   def params = [
      git_sha: sha1,
      job: job,
      id: env.BUILD_NUMBER as Integer,
   ];
   return _makeHttpRequestAndAlert("commits", "PATCH", params);
}

// status is one of "started" or "finished".
def notifyDefaultSet(sha1, status) {
   echo("Marking set-default status for ${sha1}: ${status}");
   def params = [
      git_sha: sha1,
      status: status,
   ];
   return _makeHttpRequestAndAlert("commits/set-default-status", "PATCH", params);
}

def notifyMonitoringStatus(sha1, status) {
   echo("Marking monitoring status for ${sha1}: ${status}");
   def params = [
      git_sha: sha1,
      status: status,
   ];
   return _makeHttpRequestAndAlert("commits/monitoring-status", "PATCH", params);
}

def notifyServices(sha1, services) {
   echo("Sending list of services for ${sha1}: ${services}");
   def params = [
      git_sha: sha1,
      services: services,
   ];
   return _makeHttpRequestAndAlert("commits/services", "PATCH", params);
}

def pingForStatus(job, sha1) {
   echo("Asking buildmaster for the ${job} status for ${sha1}.")
   def params = [
      git_sha: sha1,
      job: job
   ]
   def resp = _makeHttpRequestAndAlert("job-status", "POST", params);
   return resp.getContent();
}

def pingForPromptStatus(prompt, sha1) {
   echo("Asking buildmaster for the ${prompt} prompt status for ${sha1}.")
   def params = [
      git_sha: sha1,
      prompt: prompt
   ]
   def resp = _makeHttpRequestAndAlert("prompt-status", "POST", params);
   return resp.getContent();
}
