// A wrapper for deploy-webapp-core to enforce one build at a time

// If you're looking for the actual deploy script, it's temporarily in
// jobs/deploy-webapp-core.groovy!  This is a wrapper job which is used to
// enforce the old-style flow, while allowing the buildmaster to directly build
// the new-style flow.  It simply enforces one build at a time, then calls out
// to deploy-webapp.

@Library("kautils")
// Standard classes we use.
import groovy.json.JsonBuilder;
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;


// NOTE(benkraft): While we have this wrapper-script situation, please make
// sure to update deploy-webapp-core's params to match any changes you make
// here.
new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The name of a branch to deploy (can't be master).
Can also be a list of branches to deploy separated by `+` ('br1+br2+br3').
We will automatically merge these branches (plus translations if specified)
into a new branch based off master, and deploy it.""",
    ""

).addChoiceParam(
    "RUN_TESTS",
    """\
<ul>
  <li> <b>default</b>: Run relevant tests if they haven't previously
       passed at this commit. </li>
  <li> <b>yes</b>: Run all tests, even those that have already passed
       at this commit. </li>
  <li> <b>no</b>: Do not run tests before deploying (<b>dangerous!</b>
        -- do not use lightly). </li>
</ul>""",
    ["default", "yes", "no"]

).addChoiceParam(
    "DEPLOY",
    """\
<ul>
  <li> <b>default</b>: Deploy to static if there have been changes to
       the static files since the last deploy, and/or to dynamic if
       there have been changes to the dynamic files since
       the last deploy.  For tools-only changes (e.g. to Makefile), do
       not deploy at all. </li>
  <li> <b>static</b>: Deploy static (e.g. js) files to GCS, but do not
       deploy to GAE.  Only select this if you know your changes do not
       affect the server code in any way! </li>
  <li> <b>dynamic</b>: Deploy dynamic (e.g. py) files to GAE, but do
       not update GCS.  Only select this if your changes do not affect
       user-facing code (js, images) in any way!, and you're
       confident, the existing-live user-facing code will work with your
       changes. </li>
  <li> <b>both</b>: Deploy to both GCS and GAE. </li>
  <li> <b>none</b>: Do not deploy to GCS or GAE (<b>dangerous!</b> --
       do not use lightly).  Select this for tools-only changes. </li>
</ul>

<p>You may wonder: why do you need to run this job at all if you're
just changing the Makefile?  Well, it's the only way of getting files
into the master branch, so you do a 'quasi' deploy that still runs
tests/etc but doesn't actually deploy.</p>
""",
    ["default", "both", "static", "dynamic", "none"]

).addBooleanParam(
    "MERGE_TRANSLATIONS",
    """<p>If set, merge the latest translations from origin/translations into
your branch before deploying.</p>

<p>This should normally be set.  However, if you need your deploy to
go a few minutes faster, or you want to exactly reproduce a previous
 deploy, you can unset this.</p>""",
    true

).addBooleanParam(
    "ALLOW_SUBMODULE_REVERTS",
    """When set, do not give an error if the new version you're deploying has
reverted one of the git submodules to an earlier state than what
exists on the current default.  Usually such reverts are an accident
(when someone ran \"git pull\" instead of \"git p\" for instance) so
we don't allow it.  If you are purposefully reverting substate, to
revert a bug for instance, you must set this flag.""",
    false

).addBooleanParam(
    "FORCE",
    """When set, force a deploy to GAE (AppEngine) even if the version has
already been deployed. Likewise, force a copy of <i>all</i> files to
GCS (Cloud Storage), even those the md5 checksum indicate are already
present on GCS.  Also force tests to be run even if they've already passed
before at this sha1.  Note that this does not override <code>DEPLOY</code>;
we only force GAE (or GCS) if we're actually deploying to it.""",
    false

).addStringParam(
    "MONITORING_TIME",
    """How many minutes to monitor after the new version is set as default on
all modules.""",
    "5"

).addBooleanParam(
    "SKIP_PRIMING",
    """If set to True, we will change the default version without priming any
of the new instances. THIS IS DANGEROUS, and will definitely cause
disruptions to users. Only to be used in case of urgent emergency.""",
    false

).addChoiceParam(
    "CLEAN",
    """\
<ul>
  <li> <b>some</b>: Clean the workspaces (including .pyc files) but
       not genfiles. </li>
  <li> <b>most</b>: Clean the workspaces and genfiles, excluding
       js/python modules. </li>
  <li> <b>all</b>: Full clean that results in a pristine working copy. </li>
  <li> <b>none</b>: Do not clean at all. </li>
</ul>""",
    ["some", "most", "all", "none"]

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
If not specified, guess from the username of the person who started
this job in Jenkins.  Typically not set manually, but by hubot scripts
such as sun.  You can, but need not, include the leading `@`.""",
   ""

).addStringParam(
    "BUILD_USER_ID_FROM_SCRIPT",
    """(Deprecated form of DEPLOYER_USERNAME)""",
    ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


build(job: 'deploy-webapp-core', parameters: params);
