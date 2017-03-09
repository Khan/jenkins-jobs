// Groovy script to deploy a znd to khan-academy.appspot.com.
//
// A 'znd' is a non-default deploy -- a deploy of webapp that is
// never intended to be made the default for users.  It can be used
// to test something before deploying it "for realz".

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster


new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. Usually: the name of a branch to deploy.  Also
possible: a commit-sha1 to deploy. Basically, this is passed to
<code>git checkout GIT_REVISION</code>.""",
    ""

).addStringParam(
    "VERSION",
    """<b>REQUIRED</b>. The name of the this release on appengine. The
version must be in the form <code>znd-YYMMDD-&lt;your username&gt
;-other-stuff</code>. If you do not include the necessary prefix, we
will add it in for you automatically, so you can just put 'other-
stuff' here!  <i>Due to DNS limitations, please keep this short,
especially if your username is long and/or you are deploying non-
default modules!</i>""",
    ""

).addBooleanParam(
    "SKIP_I18N",
    "If set, do not build translated versions of the webapp content.",
    false

).addBooleanParam(
    "DEPLOYING_STATIC",
    """If set, deploy the static content at GIT_REVISION (js files, etc) to
GCS. Otherwise, this deploy will re-use the static content that is
currently live on the site.""",
    true

).addBooleanParam(
    "DEPLOYING_DYNAMIC",
    """If set, deploy the dynamic content at GIT_REVISION (py files, etc) to
GAE. Otherwise, this deploy will only deploy static content to GCS and
<code>MODULES</code> will be ignored.  If you uncheck this, you can
access your znd static content at
<code>https://static-&lt;version&gt;.khanacademy.org</code>""",
    true

).addStringParam(
    "MODULES",
    """A comma-separated list of modules to upload to appengine. For a list
of all modules we support, see <code>webapp/modules_util.py</code>.
The special value 'all' uploads all modules.""",
    "default"

).addChoiceParam(
    "CLEAN",
    """\
<ul>
  <li> <b>some</b>: Clean the workspaces (including .pyc files) but
       not genfiles
  <li> <b>most</b>: Clean the workspaces and genfiles, excluding
       js/ruby/python modules
  <li> <b>all</b>: Full clean that results in a pristine working copy
  <li> <b>none</b>: Don't clean at all
</ul>""",
    ["some", "most", "all", "none"]

).addBooleanParam(
    "PRIME",
    """If set, prime the version after deploying. This is only needed if many
people will be accessing this deploy.""",
    false

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.VERSION}:${params.GIT_REVISION})");


def _currentUser() {
   wrap([$class: 'BuildUser']) {
      // It seems like BUILD_USER_ID is typically an email address.
      return env.BUILD_USER_ID.split("@")[0];
   }
}


def verifyVersion() {
   if (!params.VERSION) {
      notify.fail("The VERSION parameter is required.");
   }
   if (version == "default") {
      notify.fail("Hey there, deploy-znd is for deploying " +
                  "non-defaults. Use the deploy-default job instead.");
   }
}


// `version` as converted to znd-YYMMDD-<username>-foo form.
def canonicalVersion() {
   def user = _currentUser();
   def versionMatcher = params.VERSION =~ "(znd-)?(\\d{6}-)?(${user}-)?(.*)";
   if (!versionMatcher.matches()) {
      notify.fail("Unexpected regexp-parse error for '${params.VERSION}'");
   }

   def date = versionMatcher[0][2] ?: new Date().format("yyMMdd");
   def zndName = versionMatcher[0][4];
   return "znd-${date}-${user}-${zndName}";
}


def deploy() {
   def user = _currentUser();
   onMaster('90m') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", params.GIT_REVISION);

      withEnv(["DEPLOY_VERSION=${canonicalVersion()}",
               "DEPLOY_STATIC=${params.DEPLOYING_STATIC}",
               "DEPLOY_DYNAMIC=${params.DEPLOYING_DYNAMIC}",
               "DEPLOYER_USERNAME=@${user}",
               "MODULES=${params.MODULES}",
               "SKIP_I18N=${params.SKIP_I18N}",
               "SKIP_PRIMING=${!params.PRIME}",
               "SLACK_CHANNEL=#1s-and-0s-deploys",
               "CLEAN=${params.CLEAN}",
               "FORCE=true"]) {
         sh("jenkins-tools/deploy.sh");
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                // We don't need to notify on success because deploy.sh does.
                when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   verifyVersion();
   stage("Deploying") {
      deploy();
   }
}
