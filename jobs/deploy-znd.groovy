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
    """<b>REQUIRED</b>. The name of the this release on appengine.
If your VERSION does not start with
<code>znd-YYMMDD-&lt;your username&gt-</code>, that text will be
automatically prepended.  <i>Due to DNS limitations, please keep
this short, especially if your username is long and/or you are
deploying non-default modules!</i>""",
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


// This is hard-coded.
SLACK_CHANNEL = "#1s-and-0s-deploys";


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
// This must be called within a node() (because _currentUser needs that).
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


// This should be called from within a node().
def deployToGAE() {
   if (!params.DEPLOYING_DYNAMIC) {
      return;
   }
   def args = ["deploy/deploy_to_gae.py",
               "--version=${canonicalVersion()}",
               "--modules=${params.MODULES}",
               "--no-browser", "--no-up", "--clean-versions",
               "--slack-channel=${SLACK_CHANNEL}",
               "--deployer-username=@${_currentUser()}"];
   args += params.SKIP_I18N ? ["--no-i18n"] : [];
   args += params.PRIME ? [] : ["--no-priming"];

   withSecrets() {     // we need to deploy secrets.py.
      // We need to deploy secrets.py to production, so it needs to
      // be in webapp/, not just in $SECRETS_DIR.
      sh(". ./jenkins-tools/build.lib; " +
         'cp -p "$SECRETS_DIR/secrets.py" webapp/');

      dir("webapp") {
         // Increase the the maximum number of open file descriptors.
         // This is necessary because kake keeps a lockfile open for
         // every file it's compiling, and that can easily be
         // thousands of files.  4096 is as much as linux allows.
         // We also use python -u to get maximally unbuffered output.
         // TODO(csilvers): do we need secrets for this part?
         sh("ulimit -S -n 4096; python -u ${exec.shellEscapeList(args)}");
      }
   }
}


// This should be called from within a node().
def deployToGCS() {
   // We always "deploy" to gcs, even for python-only deploys, though
   // for python-only deploys the gcs-deploy is very simple.
   def args = ["deploy/deploy_to_gcs.py", canonicalVersion()];
   if (!params.DEPLOYING_STATIC) {
      args += ["--copy-from=default"];
   }
   // We make sure deploy_to_gcs messages slack only if deploy_to_gae won't be.
   if (params.DEPLOYING_DYNAMIC) {
      args += ["--slack-channel=", "--deployer-username="];
   } else {
      args += ["--slack-channel=${SLACK_CHANNEL}",
               "--deployer-username=@${_currentUser()}"];
   }
   args += params.SKIP_I18N ? ["--no-i18n"] : [];

   withSecrets() {     // TODO(csilvers): do we actually need secrets?
      dir("webapp") {
         // Increase the the maximum number of open file descriptors.
         // This is necessary because kake keeps a lockfile open for
         // every file it's compiling, and that can easily be
         // thousands of files.  4096 is as much as linux allows.
         // We also use python -u to get maximally unbuffered output.
         sh("ulimit -S -n 4096; python -u ${exec.shellEscapeList(args)}");
      }
   }
}


def deploy() {
   onMaster('90m') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", params.GIT_REVISION);

      dir("webapp") {
        sh("make deps");
      }

      if (params.CLEAN != "none") {
         // TODO(csilvers): move clean() to a var rather than build.lib.
         sh(". ./jenkins-tools/build.lib; cd webapp; " +
            "clean ${exec.shellEscape(params.CLEAN)}");
      }

      parallel(
         "deploy-to-gae": { deployToGAE(); },
         "deploy-to-gcs": { deployToGCS(); },
         "failFast": true,
      );
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
