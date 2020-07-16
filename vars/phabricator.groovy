// Utility module for interfacing with phabricator

def submitPhabricatorHarbormasterMsg(buildPhid, type) {
   // type can be "pass", "fail", or "work"
   // See https://phabricator.khanacademy.org/conduit/method/harbormaster.sendmessage/
   if (buildPhid == "") {
      return
   }
   

   def conduitToken = readFile(
      "${env.HOME}/page-weight-phabricator-conduit-token.secret").trim();

   def message = groovy.json.JsonOutput.toJson([
      "__conduit__": [
         "token": conduitToken,
      ],
      "buildTargetPHID": buildPhid,
      "type": type,
   ])

   def body = "params=${java.net.URLEncoder.encode(message)}"

   def response = httpRequest acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: body, url: "https://phabricator.khanacademy.org/api/harbormaster.sendmessage"

   assert response.status == 200;
}
