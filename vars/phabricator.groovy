// Utility module for interfacing with phabricator
def submitHarbormasterMsg(buildPhid, type) {
   // type can be "pass", "fail", or "work"
   // See https://phabricator.khanacademy.org/conduit/method/harbormaster.sendmessage/
   if (buildPhid == "") {
      return
   }

   // TODO(dhruv): rename this secret since it's used for more than just
   // page-weight now
   def conduitToken = readFile(
      "${env.HOME}/page-weight-phabricator-conduit-token.secret").trim();

   def body = "api.token=${conduitToken}&buildTargetPHID=${buildPhid}&type=${type}"

   def response = httpRequest httpMode: 'POST', contentType: "APPLICATION_FORM", requestBody: body, url: "https://phabricator.khanacademy.org/api/harbormaster.sendmessage"

   assert response.status == 200;
}
