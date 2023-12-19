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
//import vars.withSecrets
//import vars.withTimeout


new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. Usually: the name of a branch to deploy.  Also possible:
a commit-sha1 to deploy, or a tag like phabricator/diff/&lt;id&gt; (using the latest ID
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
specify "users,static" to force a full deploy to the users service and GCS.</p>

<p>Here are some services:</p>
<ul>
  <li> <b>static</b>: Upload static (e.g. js) files to GCS. </li>
  <li> <b>kotlin-routes</b>: webapp's services/kotlin-routes/. </li>
  <li> <b>course-editing</b>: webapp's services/course-editing/. </li>
  <li> <b>donations</b>: webapp's services/donations/. </li>
</ul> """,
    "auto"

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

).addStringParam(
   "SLACK_CHANNEL",
   """The slack channel to which to send failure alerts.  Set to
@yourusername to send a Slack DM (from Slackbot).""",
   "#1s-and-0s-deploys"

).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send failure
alerts.  By default we do not send in a thread. This is the number at the end
of a message link, with a period inserted before the last 6 digits, e.g.
for the link,
https://khanacademy.slack.com/archives/C013ANU53LK/p1631811224115400,
the thread is 1631811224.115400.'""", ""

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
   // Interns and contractors might have a period in their username. 
   // Since periods are invalid characters, they need to be replaced.
   def user = _currentUser().replace(".", "-");
   // VERSION parameter needs to be lowercased.
   // Otherwise Fastly will have issue looking up the static version as it expects
   // lowercase hostname.
   VERSION = "znd-${date}-${user}-${params.VERSION}".toLowerCase();

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
def deployToGCS() {
   // We always "deploy" to gcs, even for python-only deploys, though
   // for python-only deploys the gcs-deploy is very simple.
   def args = ["deploy/deploy_to_gcs.py", VERSION,
               "--slack-channel=${params.SLACK_CHANNEL}",
               "--deployer-username=@${_currentUser()}"];
   args += params.SLACK_THREAD ? ["--slack-thread=${params.SLACK_THREAD}"] : [];
   args += params.SKIP_I18N ? ["--no-i18n"] : [];
   if (!("static" in SERVICES)) {
      args += ["--copy-from=default"];
   }

   withSecrets.slackAlertlibOnly() {  // Because we set --slack-channel
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
   withSecrets.slackAndStackdriverAlertlibOnly() {
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

// This should be called from within a node().
def deployIndexYaml() {
   if (!("index_yaml" in SERVICES)) {
      return;
   }
   // Apparently we need APPENGINE_RUNTIME= to get the imports working right.
   dir("webapp") {
      // NOTE: appengine treats deploying index.yaml as a "create"
      // operation: even if you remove entries from index.yaml appengine
      // doesn't delete those indexes from datastore (you have to do a
      // separate "index vacuum" command for that).  Thus, it's safe
      // to call pre-set-default, here in build-webapp.groovy.
      exec(["env", "APPENGINE_RUNTIME=", "gcloud", "--project=khan-academy",
            "app", "deploy", "index.yaml"]);
   }
}

// This should be called from within a node().
def deployQueueYaml() {
   if (!("queue_yaml" in SERVICES)) {
      return;
   }
   dir("webapp") {
      exec(["deploy/upload_queues.py", "create", VERSION]);
     }
}

// This should be called from within a node().
def deployPubsubYaml() {
   if (!("pubsub_yaml" in SERVICES)) {
      return;
   }
   dir("webapp") {
      exec(["deploy/upload_pubsub.py", "create", VERSION]);
   }
}

// This should be called from within a node().
def deployCronYaml() {
   if (!("cron_yaml" in SERVICES)) {
      return;
   }

   // We do not deploy ka-cron.yaml in build-webapp.groovy because,
   // unlike with e.g. pubsub.yaml, we haven't created functionality
   // to just add new rules instead of doing a full update (add + delete).
   // So it's not safe to do speculatively, and we must wait until
   // set-default time (that is, in deploy-webapp.groovy).
   // This function is included just for documentation purposes.
   return;
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
       exec(["make", "-C", "services/queryplanner",
             "deploy-gateway-config",
             "DEPLOY_VERSION=${VERSION}"]);
   }
}

def createCloudRunTags(){
   echo("Creating Cloud Run tags.")
   dir("webapp") {
      // Note: we do not update tags on the modules that we are deploying,
      // because they get assigned their tag at deploy-time, so doing it
      // here would be redundant (and would actually conflict).
      exec(["deploy/update_cloud_run_tags.py", VERSION,
            "--modules_to_ignore", SERVICES.join(',')]);
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
   args +=
      params.SLACK_THREAD ? ["--slack-thread=${params.SLACK_THREAD}"] : [];
    withSecrets.slackAlertlibOnly() {  // We pass --slack, so may talk to slack
        sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
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

      // We merge `master` into the current revision before deploying the ZND
      // so that the ZND isn't deployed using out of date processes.  We call this
      // function directly instead of relying on the merge-branches job because
      // deploy-znd isn't facilitated by buildmaster.
      kaGit.mergeBranches("master+" + params.GIT_REVISION, VERSION);

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
                           "'static,donations,kotlin-routes'");
            }
         } else {
            SERVICES = params.SERVICES.split(",").collect { it.trim() };
         }

         // Make the deps we need based on what we're deploying.  The
         // python services (default/etc) only need python deps.  The
         // goliath services build their own deps via their `make deploy`
         // rules.  That leaves the static service, which needs js deps.
         if ("static" in SERVICES) {
             sh("make npm_deps");
         }
      }

      echo("Znd Deploying to the following services: ${SERVICES.join(', ')}");
      def jobs = [:]

      for (service in SERVICES) {
         switch (service) {
            case "static":
               jobs["deploy-to-gcs"] = { deployToGCS(); };
               break;

            // These two services are a bit more complex and are handled
            // specially.
            case ( "kotlin-routes" || "course-editing" ):
               jobs["deploy-to-kotlin-services"] = { deployToKotlinServices(); };
               break;

            case "index_yaml":
               jobs["deploy-index-yaml"] = { deployIndexYaml(); };
               break;

            case "queue_yaml":
               jobs["deploy-queue-yaml"] = { deployQueueYaml(); };
               break;

            case "pubsub_yaml":
               jobs["deploy-pubsub-yaml"] = { deployPubsubYaml(); };
               break;

            case "cron_yaml":
               jobs["deploy-cron-yaml"] = { deployCronYaml(); };
               break;

            default:
               // We need to define a new variable so that we don't
               // pass the loop variable into the closure: it may have
               // changed before the closure executes.  See, e.g.:
               // http://blog.freeside.co/2013/03/29/groovy-gotcha-for-loops-and-closure-scope/
               def serviceAgain = service;
               jobs["deploy-to-${serviceAgain}"] = { deployToService(serviceAgain); };
               break;
         }
      }
      jobs["deploy-to-gateway-config"] = { deployToGatewayConfig(); };
      jobs["failFast"] = true;

      parallel(jobs);

      createCloudRunTags();

      _sendSimpleInterpolatedMessage(
         alertMsgs.JUST_DEPLOYED.text,
         [deployUrl: "https://prod-${VERSION}.khanacademy.org",
          version: VERSION,
          branches: params.GIT_REVISION,
          services: SERVICES.join(', ') ?: 'nothing (?!)',
          logsUrl: ("https://console.cloud.google.com/logs/viewer?" +
                    "project=khan-academy&resource=gae_app%2F" +
                    "version_id%2F" + VERSION)]);
   }
}


// We use a separate worker type, identical to build-worker, so znds don't make
// a mess of our build caches for the main deploy.
onWorker('znd-worker', '3h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: CHAT_SENDER,
                   emoji: EMOJI,
                   // We don't need to notify on success because deploy.sh does.
                   when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      determineVersion();
      stage("Deploying") {
         deploy();
      }
   }
}
