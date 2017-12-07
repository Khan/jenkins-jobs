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
    println("${resource} response: ${response.status}: ${response.content}");
}

def _notifyTests(result, master, branches) {
    // TODO: when we've decided exactly how we want to do branch merging,
    // we will want to use the merge's resulting SHA1 instead of
    // master+branches
    def params = [
        git_sha: ([master] + branches).join("+"),
        result: result,
    ];
    return _makeHttpRequest("commits", "PATCH", params);
}

def testsFailed(master, branches) {
    return _notifyTests("failed", master, branches);
}

def testsSucceeded(master, branches) {
    return _notifyTests("success", master, branches);
}

def testsAborted(master, branches) {
    return _notifyTests("aborted", master, branches);
}
