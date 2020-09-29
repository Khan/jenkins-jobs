// Groovy script to deploy a znd to khanacademy.org
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
    """<b>REQUIRED</b>. Usually: the name of a branch to deploy.  Also possible:
a commit-sha1 to deploy, or a tag like phabricator/diff/<id> (using the latest ID
from the diff's "history" tab or <code>revisionid-to-diffid.sh D#####</code>).
Basically, this is passed to <code>git checkout GIT_REVISION</code>.""",
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

).addStringParam(
    "SERVICES",
    """<p>A comma-separated list of services we wish to deploy (see below for
options), or the special value "auto", which says to choose the services to
deploy automatically based on what files have changed.  For example, you might
specify "dynamic,static" to force a full deploy to GAE and GCS.</p>

<p>Here are some services:</p>
<ul>
  <li> <b>dynamic</b>: Upload dynamic (e.g. py) files to GAE. </li>
  <li> <b>static</b>: Upload static (e.g. js) files to GCS. </li>
  <li> <b>kotlin-routes</b>: webapp's services/kotlin-routes/. </li>
  <li> <b>course-editing</b>: webapp's services/course-editing/. </li>
  <li> <b>donations</b>: webapp's services/donations/. </li>
</ul> """,
    "dynamic,static"

).addBooleanParam(
    "SKIP_I18N",
    "If set, do not build translated versions of the webapp content.",
    false

// TODO(benkraft): Modify this script to think in terms of services,
// like build-webapp does, if we ever have znds for other services.

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
   "SLACK_CHANNEL",
   """The slack channel to which to send failure alerts.  Set to
@yourusername to send a Slack DM (from Slackbot).""",
   "#1s-and-0s-deploys"

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

// The list of services to which to deploy.
SERVICES = null;

// This is hard-coded.
CHAT_SENDER =  'Mr Monkey';
EMOJI = ':monkey_face:';


def _currentUser() {
   wrap([$class: 'BuildUser']) {
      // It seems like BUILD_USER_ID is typically an email address.
      def username = env.BUILD_USER_ID.split("@")[0]
      if (username.size() > 8) {
         // We take the first 8 characters -- that makes sure you have at least
         // five left for the version name (see determineVersion below).
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
   // service-name we have is `progress-reports`.
   //
   // Note that the issue we're trying to protect against is your znd
   // making an inter-service call to another service.  So even if
   // you're not deploying progress-reports yourself, your code
   // may want to talk to the progress-reports service, and we
   // need to make sure that when it does the hostname isn't too long.
   if ("${VERSION}-dot-progress-reports-dot-khan-academy".length() > 63) {
      notify.fail("Your version-name is too long for DNS! " +
                  "Pick a shorter VERSION");
   }
}


// This should be called from within a node().
def deployToGAE() {
   if (!("dynamic" in SERVICES)) {
      return;
   }
   def args = ["deploy/deploy_to_gae.py",
               "--version=${VERSION}",
               "--modules=${params.MODULES}",
               "--no-browser", "--no-up",
               "--slack-channel=${params.SLACK_CHANNEL}",
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
   if (!("static" in SERVICES)) {
      args += ["--copy-from=default"];
   }
   // We make sure deploy_to_gcs messages slack only if deploy_to_gae won't be.
   if ("dynamic" in SERVICES) {
      args += ["--slack-channel=", "--deployer-username="];
   } else {
      args += ["--slack-channel=${params.SLACK_CHANNEL}",
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

// This should be called from within a node().
def deployToService(service) {
   withSecrets() {     // TODO(benkraft): do we actually need secrets?
      dir("webapp") {
         exec(["make", "-C", "services/${service}", "deploy",
               "ALREADY_RAN_TESTS=1",
               "DEPLOY_VERSION=${VERSION}"]);
      }
   }
}


// This should be called from within a node().
// HACK: This is a workaround to manage a bug in our current deploy system,
// where running gradlew in two services in parallel causes a conflict in
// the cache dir (See INFRA-3594 for full details). Here we're careful to
// build kotlin services in series. Once this bug is resolved, we can remove
// this, and let kotlin services default to the shared deployToService again.
def deployToKotlinServices() {
   for (service in SERVICES) {
      if (service in ['course-editing', 'kotlin-routes']) {
         deployToService(service);
      }
   }
}

// When we deploy a change to a service, it may change the overall federated
// graphql schema. We store this overall schema in a version labeled json file
// stored on GCS.
//
// We only _really_ need to do this if the schema changed, so we could skip it
// for static deploys or for service deploys that don't change the schema, but
// uploading the schema here takes less than a second, so it doesn't hurt to
// just do it always.
def deployToGatewayConfig() {
   dir("webapp") {
       exec(["make", "-C", "services/graphql-gateway",
             "deploy-gateway-config",
             "DEPLOY_VERSION=${VERSION}"]);
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
                "--slack=${params.SLACK_CHANNEL}",
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
         sh("make python_deps");

         def shouldDeployArgs = ["deploy/should_deploy.py"];

         if (params.SERVICES == "auto") {
            try {
               SERVICES = exec.outputOf(shouldDeployArgs).split("\n");
            } catch(e) {
               notify.fail("Automatic detection of what to deploy failed. " +
                           "You can likely work around this by setting " +
                           "SERVICES on your deploy by a comma-separated " +
                           "list of services. For instance: " +
                           "'dynamic,static,donations,kotlin-routes'");
            }
         } else {
            SERVICES = params.SERVICES.split(",");
         }

         // Make the deps we need based on what we're deploying.  The
         // python services (default/etc) only need python deps.  The
         // goliath services build their own deps via their `make deploy`
         // rules.  That leaves the static service, which needs js deps.
         // TODO(csilvers): make it so we don't have to do this for
         //                 graphql-gateway deploys, right now they call
         //                 `make genfiles/gateway_config.json` which runs js.
         if ("static" in SERVICES || "graphql-gatway" in SERVICES) {
             // Ideally we'd just run `make webapp_npm_deps`, but we've
             // had trouble, with that not updating node_modules/
             // properly, so we run `make fix_deps` instead to be safe.
             sh("make fix_deps");
         }
      }

      echo("Znd Deploying to the following services: ${SERVICES.join(', ')}");
      def jobs = [:]

      for (service in SERVICES) {
         switch (service) {
            case "dynamic":
               jobs["deploy-to-gae"] = { deployToGAE(); };
               break;

            case "static":
               jobs["deploy-to-gcs"] = { deployToGCS(); };
               break;

            // These two services are a bit more complex and are handled
            // specially.
            case ( "kotlin-routes" || "course-editing" ):
               jobs["deploy-to-kotlin-services"] = { deployToKotlinServices(); };
               break;

            default:
               // We need to define a new variable so that we don't pass the loop
               // variable into the closure: it may have changed before the
               // closure executes.  See for example
               // http://blog.freeside.co/2013/03/29/groovy-gotcha-for-loops-and-closure-scope/
               def serviceAgain = service;
               jobs["deploy-to-${serviceAgain}"] = { deployToService(serviceAgain); };
               break;
         }
      }
      jobs["deploy-to-gateway-config"] = { deployToGatewayConfig(); };
      jobs["failFast"] = true;

      parallel(jobs);

      _sendSimpleInterpolatedMessage(
         alertMsgs.JUST_DEPLOYED.text,
         [deployUrl: "https://prod-${VERSION}.khanacademy.org",
          version: VERSION,
          branches: params.GIT_REVISION,
          services: SERVICES.join(', ') ?: 'nothing (?!)',
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
   notify([slack: [channel: params.SLACK_CHANNEL,
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
