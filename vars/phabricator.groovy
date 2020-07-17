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
