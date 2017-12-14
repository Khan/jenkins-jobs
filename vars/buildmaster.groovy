// Utility module for interfacing with the buildmaster

import groovy.json.JsonBuilder;


def BUILDMASTER_TOKEN = readFile(
   "${env.HOME}/buildmaster-api-token.secret").trim();

// Make an API request to the buildmaster
// `params` is expected to be a map
def _makeHttpRequest(resource, httpMode, params) {
    def response = httpRequest(
        acceptType: "APPLICATION_JSON",
        contentType: "APPLICATION_JSON",
        customHeaders: [[name: 'X-Buildmaster-Token',
                         value: BUILDMASTER_TOKEN]],
        httpMode: httpMode,
        requestBody: JsonBuilder(params).toString(),
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
