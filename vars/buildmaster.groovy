// Utility module for interfacing with the buildmaster
import groovy.json.JsonBuilder;
import groovy.transform.Field;

// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.notify
//import vars.retry


@Field BUILDMASTER_TOKEN = null;

BUILDMASTER_OUTAGE = [
   "severity": "error",
   "simpleMessage": true,
   "text": _textWrap("""\
:ohnoes: Jenkins is unable to reach buildmaster right now while trying to do:
%(step)s. the response status is %(status). Ping <!subteam^S41PPSJ21> to
check the buildmaster https://buildmaster.khanacademy.org/ping.
Perhaps buildmaster is down.
""")];

SLACK_CHANNEL = "#infrastructure-devops";
CHAT_SENDER =  'Mr Monkey';
EMOJI = ':monkey_face:';


def _interpolateString(def s, def interpolationArgs) {
   // Arguments to replaceAll().  `all` is the entire regexp match,
   // `keyword` is the part that matches our one parenthetical group.
   def interpolate = { all, keyword -> interpolationArgs[keyword]; };
   def interpolationPattern = "%\\(([^)]*)\\)s";
   return s.replaceAll(interpolationPattern, interpolate);
}

def _sendSimpleInterpolatedMessage(def rawMsg, def interpolationArgs) {
   def msg = _interpolateString(
      "@dev-support: ${rawMsg}", interpolationArgs);

   // ping "#infrastructure-devops" channel when buildmaster is down
   def args = ["jenkins-jobs/alertlib/alert.py",
               "--slack=${SLACK_CHANNEL}",
               "--chat-sender=${CHAT_SENDER}",
               "--icon-emoji=${EMOJI}",
               "--slack-simple-message"];

   // Secrets required to talk to slack.
   withSecrets.ifAvailable() {
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
def _makeHttpRequest(resource, httpMode, params) {
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
         return response;
      }
   } catch (e) {
      // Ideally, we'd just catch hudson.AbortException, but for some reason
      // it's not being caught properly.
      // httpRequest throws exceptions when buildmaster responds with status
      // code >=400
      notify.fail("Error notifying buildmaster:\n" + e.getMessage());
   }
}

// an wrapper to call _makeHttpRequest and check response status
// if buildmaster is down, alert loudly and ping @dev-support
def _talkToBuildMaster(resource, httpMode, params) {
   try {
      def resp = _makeHttpRequest(resource, httpMode, params)
      if (resp.getStatus() == 200) {
         return resp.getContent();
      }
   } catch (e) {
      echo("Error getting job status: ${e}");
      return
   }

   echo("Got ${resp.getStatus()}, perhaps buildmaster is down.");
   _sendSimpleInterpolatedMessage(
      BUILDMASTER_OUTAGE,
      [step: "${resource} + ${httpMode}",
      status: "${resp.getStatus()}"]);
   return
}

def notifyStatus(job, result, sha1) {
   def params = [
      git_sha: sha1,
      job: job,
      result: result,
      id: env.BUILD_NUMBER as Integer,
   ];
   return _talkToBuildMaster("commits", "PATCH", params);
}

def notifyMergeResult(commitId, result, sha1, gae_version_name) {
   echo("Marking commit #${commitId} as ${result}: ${sha1}");
   def params = [
      commit_id: commitId,
      result: result,
      git_sha: sha1,
      gae_version_name: gae_version_name
   ];
   return _talkToBuildMaster("commits/merge", "PATCH", params);
}

def notifyWaiting(job, sha1, result) {
   echo("Setting for ${sha1}: ${result}");
   def params = [
      git_sha: sha1,
      job: job,
      result: result,
   ];
   return _talkToBuildMaster("commits/waiting", "POST", params);
}

def notifyId(job, sha1) {
   echo("Phoning home to log job ID #${env.BUILD_NUMBER} for ${sha1} ${job}");
   def params = [
      git_sha: sha1,
      job: job,
      id: env.BUILD_NUMBER as Integer,
   ];
   return _talkToBuildMaster("commits", "PATCH", params);
}

// status is one of "started" or "finished".
def notifyDefaultSet(sha1, status) {
   echo("Marking set-default status for ${sha1}: ${status}");
   def params = [
      git_sha: sha1,
      status: status,
   ];
   return _talkToBuildMaster("commits/set-default-status", "PATCH", params);
}

def notifyMonitoringStatus(sha1, status) {
   echo("Marking monitoring status for ${sha1}: ${status}");
   def params = [
      git_sha: sha1,
      status: status,
   ];
   return _talkToBuildMaster("commits/monitoring-status", "PATCH", params);
}

def notifyServices(sha1, services) {
   echo("Sending list of services for ${sha1}: ${services}");
   def params = [
      git_sha: sha1,
      services: services,
   ];
   return _talkToBuildMaster("commits/services", "PATCH", params);
}

def pingForStatus(job, sha1) {
   echo("Asking buildmaster for the ${job} status for ${sha1}.")
   def params = [
      git_sha: sha1,
      job: job
   ]
   return _talkToBuildMaster("job-status", "POST", params);
}

def pingForPromptStatus(prompt, sha1) {
   echo("Asking buildmaster for the ${prompt} prompt status for ${sha1}.")
   def params = [
      git_sha: sha1,
      prompt: prompt
   ]
   return _talkToBuildMaster("prompt-status", "POST", params);
}
