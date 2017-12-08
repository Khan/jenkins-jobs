// Utility module for interfacing with the buildmaster
import groovy.transform.Field;

import groovy.json.JsonBuilder;


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
   echo('_makeHttpRequest');
   initializeBuildmasterToken();
   def response = httpRequest(
      acceptType: "APPLICATION_JSON",
      contentType: "APPLICATION_JSON",
      customHeaders: [[name: 'X-Buildmaster-Token',
                       value: BUILDMASTER_TOKEN]],
      httpMode: httpMode,
      requestBody: new JsonBuilder(params).toString(),
      url: "https://buildmaster.khanacademy.org/${resource}");
   echo("${resource} response: ${response.status}: ${response.content}");
}

def notifyStatus(job, result, sha1) {
   def params = [
      git_sha: sha1,
      job: job,
      result: result,
   ];
   return _makeHttpRequest("commits", "PATCH", params);
}

def notifyMergeResult(commitId, result, sha1) {
   def params = [
      commit_id: commitId,
      result: result,
      git_sha: sha1,
   ];
   return _makeHttpRequest("commits/merge", "PATCH", params);
}
