// Groovy script to deploy a znd to khan-academy.appspot.com.
//
// A 'znd' is a non-default deploy -- a deploy of webapp that is
// never intended to be made the default for users.  It can be used
// to test something before deploying it "for realz".

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.clean
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


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

).addBooleanParam(
    "HIGHMEM_INSTANCE",
    """If set, this version will be deployed on a highmem instance class rather
than the instance class used for regular default deployes to aid in filling
caches and reducing timeouts. If you will be sending significant traffic to your
znd, consider setting this to false since they are more expensive instances.""",
    true

).addStringParam(
    "PHAB_REVISION",
    """The Phabricator revision ID for this build. This field should only be
defined if the znd-deploy originates from the Phabricator Herald Build Plan 6.
It helps us access data from the a revision, namely the summary, which we need
in order to know the appropriate fixtures to post links for in Phabricator.""",
    ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.VERSION}:${params.GIT_REVISION})");


// This is hard-coded.
SLACK_CHANNEL = "#1s-and-0s-deploys";
CHAT_SENDER =  'Mr Monkey';
EMOJI = ':monkey_face:';


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
               "--no-browser", "--no-up",
               "--slack-channel=${SLACK_CHANNEL}",
               "--deployer-username=@${_currentUser()}"];
   args += params.SKIP_I18N ? ["--no-i18n"] : [];
   args += params.PRIME ? [] : ["--no-priming"];
   args += params.HIGHMEM_INSTANCE ? ["--highmem-instance"] : [];

   withSecrets() {     // we need to deploy secrets.py.
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

// TODO(colin): these messaging functions are mostly duplicated from
// deploy-webapp.groovy and deploy-history.groovy.  We should probably set up
// an alertlib (or perhaps just slack messaging) wrapper, since similar
// functions keep cropping up everywhere.

@NonCPS     // for replaceAll()
def _interpolateString(def s, def interpolationArgs) {
   // Arguments to replaceAll().  `all` is the entire regexp match,
   // `keyword` is the part that matches our one parenthetical group.
   def interpolate = { all, keyword -> interpolationArgs[keyword]; };
   def interpolationPattern = "%\\(([^)]*)\\)s";
   return s.replaceAll(interpolationPattern, interpolate);
}

def _sendSimpleInterpolatedMessage(def rawMsg, def interpolationArgs) {
    def msg = _interpolateString(
        "${_currentUser()}: ${rawMsg}", interpolationArgs);

    def args = ["jenkins-jobs/alertlib/alert.py",
                "--slack=${SLACK_CHANNEL}",
                "--chat-sender=${CHAT_SENDER}",
                "--icon-emoji=${EMOJI}",
                "--slack-simple-message"];
    // Secrets required to talk to slack.
    withSecrets() {
        sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
    }
}

// Run a script that crafts a comment with the necessary fixture links and
// posts that comment to Phab. See script at webapp/deploy/post_znd_fixture_links.py
// for full description of the workflow from including `Components for Review:` in
// diff summary to running a znd deploy to posting fixture links to Phab.
def _sendCommentToPhabricator() {
   def args = ["deploy/post_znd_fixture_links.py", PHAB_REVISION, "${canonicalVersion()}"];

   withSecrets() {      // we need secrets to talk to phabricator
      dir("webapp") {
         sh("${exec.shellEscapeList(args)}");
      }
   }
}

def deploy() {
   withTimeout('90m') {
      alertMsgs = load("${pwd()}/../workspace@script/jobs/deploy-webapp_slackmsgs.groovy");

      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                             params.GIT_REVISION);

      dir("webapp") {
         clean(params.CLEAN);
         sh("make deps");
      }

      parallel(
         "deploy-to-gae": { deployToGAE(); },
         "deploy-to-gcs": { deployToGCS(); },
         "failFast": true,
      );

      def deployUrl = "https://${canonicalVersion()}-dot-khan-academy.appspot.com";
      // The CMS endpoints must be handled on the vm module. However,
      // the rules in dispatch.yaml only match *.khanacademy.org,
      // so the routing doesn't work in ZNDs; therefore, we show
      // a link directly to the vm module if it is deployed
      // or a suggestion to deploy the vm module if it is not.
      def vmIsDeployed = params.MODULES.split(",").contains("vm");
      def vmMessage = (
         " Note that if you want to test the CMS or the publish pages " +
         "(`/devadmin/content` or `/devadmin/publish`), " +
         (vmIsDeployed
          ? "you need to do so on the " +
            "<https://${canonicalVersion()}-dot-vm-dot-khan-academy.appspot.com|" +
            "vm module> instead."
          : "you need to <https://jenkins.khanacademy.org/job/deploy/job/deploy-znd/build|" +
            "redeploy this ZND> and add `vm` to the `MODULES` parameter in Jenkins."));
      _sendSimpleInterpolatedMessage(
         alertMsgs.JUST_DEPLOYED.text + vmMessage,
         [deployUrl: deployUrl,
          version: canonicalVersion()]);

      // If the phab_revision param exists than this znd-deploy must have
      // originated from the Herald Build Plan 6 and we can expect that the
      // revision summary includes some fixtures that we want to post to Phab.
      if (params.PHAB_REVISION) {
         _sendCommentToPhabricator()
      }
   }
}


notify([slack: [channel: SLACK_CHANNEL,
                sender: CHAT_SENDER,
                emoji: EMOJI,
                // We don't need to notify on success because deploy.sh does.
                when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "2h"]) {
   // TODO(benkraft): Run deploy-znd on the build workers, so we can run
   // several at a time.
   verifyVersion();
   stage("Deploying") {
      deploy();
   }
}
