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
//import vars.onWorker
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
This must consist of only lowercase letters, numbers, and hyphens.
The "znd-", date, and your username will be automatically prepended,
so don't include those.  <i>Due to DNS limitations, please keep this
extremely short, especially if your username is long and/or you are
deploying non-default modules!</i>""",
    ""

).addBooleanParam(
    "SKIP_I18N",
    "If set, do not build translated versions of the webapp content.",
    false

// TODO(benkraft): Modify this script to think in terms of services,
// like build-webapp does, if we ever have znds for other services.
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

// Since we use build workers, there's no need to serialize znd deploys.
).allowConcurrentBuilds(

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.VERSION}:${params.GIT_REVISION})");

// The full exact version-name we will use.
VERSION = null;

// This is hard-coded.
SLACK_CHANNEL = "#1s-and-0s-deploys";
CHAT_SENDER =  'Mr Monkey';
EMOJI = ':monkey_face:';


def _currentUser() {
   wrap([$class: 'BuildUser']) {
      // It seems like BUILD_USER_ID is typically an email address.
      def username = env.BUILD_USER_ID.split("@")[0]
      if (username.size() > 8) {
         // We take the first 8 characters -- that makes sure you have at least
         // one left for the version name (see determineVersion below).
         // TODO(benkraft): Something more principled.  These days, we could
         // probably remove the (date and) user entirely, and store that data
         // in the buildmaster.
         return username[0..<8]
      } else {
         return username
      }
   }
}


def determineVersion() {
   if (!params.VERSION) {
      notify.fail("The VERSION parameter is required.");
   }
   if (version.size() >= 3 && version[0..<3] == "znd") {
      notify.fail("No need to include the 'znd-YYMMDD-username' prefix! " +
                  "We'll add it for you.");
   }

   def date = new Date().format("yyMMdd");
   VERSION = "znd-${date}-${_currentUser()}-${params.VERSION}";

   // DNS has a limit of 63 bytes per hostname-component.  This
   // version can yield hostname-components like
   // `$VERSION-dot-<service>-dot-khan-academy`, so we need to
   // make sure that that is always <64 chars.  The biggest
   // service-name we have is `activation-reporting`.
   //
   // Note that the issue we're trying to protect against is your znd
   // making an inter-service call to another service.  So even if
   // you're not deploying activation-reporting yourself, your code
   // may want to talk to the activation-reporting service, and we
   // need to make sure that when it does the hostname isn't too long.
   if ("${VERSION}-dot-activation-reporting-dot-khan-academy".length() > 63) {
      notify.fail("Your version-name is too long for DNS! " +
                  "Pick a shorter VERSION");
   }
}


// The URL for this znd.  Optionally, for dynamic deploys, a module may be
// passed (e.g. "vm"), which will be included in the URL; the argument is
// ignored for static-only deploys as those respect dispatch.yaml rules as
// normal.
def deployedUrl(def module) {
   if (!params.DEPLOYING_DYNAMIC) {
      return "https://static-${VERSION}.khanacademy.org";
   } else if (module) {
      return ("https://${VERSION}-dot-${module}-dot-khan-academy.appspot.com");
   } else {
      return "https://${VERSION}-dot-khan-academy.appspot.com";
   }
}


// This should be called from within a node().
def deployToGAE() {
   if (!params.DEPLOYING_DYNAMIC) {
      return;
   }
   def args = ["deploy/deploy_to_gae.py",
               "--version=${VERSION}",
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
   def args = ["deploy/deploy_to_gcs.py", VERSION];
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
   def args = ["deploy/post_znd_fixture_links.py", PHAB_REVISION, VERSION];

   withSecrets() {      // we need secrets to talk to phabricator
      dir("webapp") {
         sh("${exec.shellEscapeList(args)}");
      }
   }
}

def deploy() {
   withTimeout('150m') {
      // In principle we should fetch from workspace@script which is where this
      // script itself is loaded from, but that doesn't exist on znd-workers
      // and our checkout of jenkins-jobs will work fine.
      alertMsgs = load("${pwd()}/jenkins-jobs/jobs/deploy-webapp_slackmsgs.groovy");

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

      def services = []
      if (params.DEPLOYING_STATIC) {
         services += ["static"];
      }
      if (params.DEPLOYING_DYNAMIC) {
         services += ["dynamic (modules: ${params.MODULES})"];
      }

      _sendSimpleInterpolatedMessage(
         alertMsgs.JUST_DEPLOYED.text,
         [deployUrl: deployedUrl(""),
          version: VERSION,
          branches: params.GIT_REVISION,
          services: services.join(', ') ?: 'nothing (?!)',
          logsUrl: ("https://console.cloud.google.com/logs/viewer?" +
                    "project=khan-academy&resource=gae_app%2F" +
                    "version_id%2F" + VERSION)]);

      // If the phab_revision param exists than this znd-deploy must have
      // originated from the Herald Build Plan 6 and we can expect that the
      // revision summary includes some fixtures that we want to post to Phab.
      if (params.PHAB_REVISION) {
         _sendCommentToPhabricator()
      }
   }
}


// We use a separate worker type, identical to build-worker, so znds don't make
// a mess of our build caches for the main deploy.
onWorker('znd-worker', '3h') {
   notify([slack: [channel: SLACK_CHANNEL,
                   sender: CHAT_SENDER,
                   emoji: EMOJI,
                   // We don't need to notify on success because deploy.sh does.
                   when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      determineVersion();
      stage("Deploying") {
         deploy();
      }
   }
}
