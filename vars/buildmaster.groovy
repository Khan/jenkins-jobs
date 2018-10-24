// Utility module for interfacing with the buildmaster
import groovy.json.JsonBuilder;
import groovy.transform.Field;

// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.notify


@Field BUILDMASTER_TOKEN = null;


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
   } catch (e) {
      // Ideally, we'd just catch hudson.AbortException, but for some reason
      // it's not being caught properly.
      // httpRequest throws exceptions when buildmaster responds with status
      // code >=400
      notify.fail("Error notifying buildmaster:\n" + e.getMessage());
   }
}

def notifyStatus(job, result, sha1) {
   def params = [
      git_sha: sha1,
      job: job,
      result: result
   ];
   return _makeHttpRequest("commits", "PATCH", params);
}

def notifyMergeResult(commitId, result, sha1, gae_version_name) {
   echo("Marking commit #${commitId} as ${result}: ${sha1}");
   def params = [
      commit_id: commitId,
      result: result,
      git_sha: sha1,
      gae_version_name: gae_version_name
   ];
   return _makeHttpRequest("commits/merge", "PATCH", params);
}

def notifyWaiting(job, sha1, result) {
   echo("Setting for ${sha1}: ${result}");
   def params = [
      git_sha: sha1,
      job: job,
      result: result,
   ];
   return _makeHttpRequest("commits/waiting", "POST", params);
}

def notifyId(job, sha1) {
   echo("Phoning home to log job ID #${env.BUILD_NUMBER} for ${sha1} ${job}");
   def params = [
      git_sha: sha1,
      job: job,
      id: env.BUILD_NUMBER as Integer,
   ];
   return _makeHttpRequest("commits", "PATCH", params);
}

// status is one of "started" or "finished".
def notifyDefaultSet(sha1, status) {
   echo("Marking set-default status for ${sha1}: ${status}");
   def params = [
      git_sha: sha1,
      status: status,
   ];
   return _makeHttpRequest("commits/set-default-status", "PATCH", params);
}

def notifyServices(sha1, services) {
   echo("Sending list of services for ${sha1}: ${services}");
   def params = [
      git_sha: sha1,
      services: services,
   ];
   return _makeHttpRequest("commits/services", "PATCH", params);
}

def pingForStatus(job, sha1) {
   echo("Asking buildmaster for the ${job} status for ${sha1}.")
   def params = [
      git_sha: sha1,
      job: job
   ]
   def resp = _makeHttpRequest("job-status", "POST", params)

   if (resp.getStatus() == 200) {
      return resp.getContent();
   }
   echo("Received a non-200 response, perhaps buildmaster is down.");
   return
}
