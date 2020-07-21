// Utility module for interfacing with phabricator
import groovy.transform.Field;

import java.net.URLEncoder;

@Field PHABRICATOR_TOKEN = null;

def initializeToken() {
   if (!PHABRICATOR_TOKEN) {
      // TODO(dhruv): rename this secret since it's used for more than just
      // page-weight now
      PHABRICATOR_TOKEN = readFile(
        "${env.HOME}/page-weight-phabricator-conduit-token.secret").trim();
   }
}

def submitHarbormasterMsg(buildPhid, type) {
   // type can be "pass", "fail", or "work"
   // See https://phabricator.khanacademy.org/conduit/method/harbormaster.sendmessage/
   if (buildPhid == "") {
      return
   }
   initializeToken()

   def body = "api.token=${PHABRICATOR_TOKEN}&buildTargetPHID=${buildPhid}&type=${type}"

   def response = httpRequest consoleLogResponse: true, httpMode: 'POST', contentType: "APPLICATION_FORM", requestBody: body, url: "https://phabricator.khanacademy.org/api/harbormaster.sendmessage"

   assert response.status == 200;
}

def linkHarbormasterToJenkins(buildPhid, buildUrl) {
   if (buildPhid == "") {
      return
   }

   initializeToken()

   def message = "View on Jenkins"

   def body = "api.token=${PHABRICATOR_TOKEN}&buildTargetPHID=${buildPhid}"+
              "&artifactKey=jenkins-webapp-test-results&artifactType=uri" +
              "&artifactData[name]=${java.net.URLEncoder.encode(message)}" +
              "&artifactData[ui.external]=1" +
              "&artifactData[uri]=${java.net.URLEncoder.encode(buildUrl)}"

   def response = httpRequest consoleLogResponse: true, httpMode: 'POST', contentType: "APPLICATION_FORM", requestBody: body, url: "https://phabricator.khanacademy.org/api/harbormaster.createartifact"

   assert response.status == 200;
}

def submitDifferentialComment(revisionId, message) {
   if (revisionId == "") {
      return
   }

   initializeToken()

   def body = "api.token=${PHABRICATOR_TOKEN}" +
              "&objectIdentifier=${revisionId}"+
              "&transactions[0][type]=comment" +
              "&transactions[0][value]=${java.net.URLEncoder.encode(message)}"


   def response = httpRequest consoleLogResponse: true, httpMode: 'POST', contentType: "APPLICATION_FORM", requestBody: body, url: "https://phabricator.khanacademy.org/api/differential.revision.edit"

   assert response.status == 200;
}

// Report on the results of a test result from jenkins, sending a comment to phabricator
//
// - revisionId should be the revision identifier for the phabrictor diff to comment on
// - testResultSummary should be the results of a `junit` pipeline step,
//     reporting on passed
// - failures should be a list of maps, each with string keys: className, name, url
def reportTestResults(revisionId, testResults, failures) {
    def text = "**Test Status**: " +
               "Passed: ${testResults.getPassCount()}, " +
               "Failed: ${testResults.getFailCount()}, " +
               "Skipped: ${testResults.getSkipCount()}\n"

    for (def failure in failures) {
        text += "[[${failure.url}| ${failure.className}.${failure.name}]]\n"
    }

    // TODO(dhruv): we might also consider sending this through
    // harbormaster.sendMessage, but I don't think that alerts a dev to new results.
    submitDifferentialComment(revisionId, text)
}
