// Utility module for interfacing with the buildmaster

import groovy.json.JsonBuilder;


def _buildmasterURL(resource) {
    return "https://buildmaster.khanacademy.org/${resource}";
}

// Make an API request to the buildmaster
// `params` is expected to be a map
def _makeHttpRequest(resource, httpMode, params) {
    def response = httpRequest(
        acceptType: "APPLICATION_JSON",
        contentType: "APPLICATION_JSON",
        httpMode: httpMode,
        requestBody: JsonBuilder(params).toString(),
        url: _buildmasterURL(resource));
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
