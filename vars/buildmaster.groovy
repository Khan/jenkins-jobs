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

def _notifyTests(result, branches) {
    def params = [
        git_sha: branches.join("+"),
        result: result,
    ];
    return _makeHttpRequest("commits", "PATCH", params);
}

def testsFailed(branches) {
    return _notifyTests("failed", branches);
}

def testsSucceeded(branches) {
    return _notifyTests("success", branches);
}

def testsAborted(branches) {
    return _notifyTests("aborted", branches);
}
