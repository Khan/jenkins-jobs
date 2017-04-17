// The pipeline job to upload i18n .index/.chunk files to GCS.
// .index/.chunk files are our own processed form of .mo files,
// used for translation.  We upload these to GCS, where webapp
// accesses them when it needs to translate some content at
// runtime.  (This is for datastore content and the occassional
// python string.)
//
// This job is called automatically every time we download content
// from crowdin.


@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.withSecrets


new Setup(steps

).addStringParam(
    "LOCALES",
    """A whitespace-separated list of locales to upload to GCS.
<b>required</b>""",
    ""

).addStringParam(
    "GIT_TAG",
    """The git tag corresponding to the release that we should build our
files against.  (But see TRANSLATIONS_COMMIT, below.) This needs to be
one of the `gae-*` tags, not an arbitrary git commit-ish, because we
parse the static-content version out of it.""",
    ""

).addStringParam(
    "TRANSLATIONS_COMMIT",
    """The git commit-ish to sync intl/translations subdirectory to when
building our files.  This means that when we build our genfiles -- the
pofiles and the js/css files -- we are doing so in a hybrid
\"frankenfile\" mode, with the source code as specified by
GAE_VERSIONS, but with the translations as specified by
TRANSLATIONS_COMMIT.""",
    "origin/master"

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.LOCALES})";


def syncRepos() {
   onMaster("10m") {
      def gitTag = params.GIT_TAG;
      if (!gitTag) {
         // We sync to master so we can get the git-tag of the current
         // live version of webapp.  Then we sync to that.
         kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
         dir("webapp") {
            // We want the last release that's successfully been tagged.
            // This can differ from the current release when a deploy
            // is going on (after deploying to GAE and before finishing).
            gitTag = exec.outputOf(["deploy/git_tags.py"]).split("\n")[-1];
         }
      }
      kaGit.safeSyncTo("git@github.com:Khan/webapp", gitTag);

      // We have to do things in this weird way to fake out build.lib
      // into not complaining that we're using internal functions.
      // TODO(csilvers): don't use build.lib internals.
      sh(". jenkins-tools/build.lib; cd webapp/intl/translations; " +
         "_safe_fetch; " +
         "_safe_destructive_checkout" +
         "   ${exec.shellEscape(params.TRANSLATIONS_COMMIT)}");
   }
}


def runScript() {
   onMaster("23h") {
      withEnv(["GIT_TAG=${params.GIT_TAG}",
               "I18N_GCS_UPLOAD_LOCALES=${params.LOCALES}"]) {
         withSecrets() {
            // TODO(csilvers): see if we can break up this script into
            // pieces, so we can put using-a-lot-of-memory only around
            // the parts that use a lot of memory.
            lock("using-a-lot-of-memory") {
               sh("jenkins-tools/i18n-gcs-upload.sh");
            }
         }
      }
   }
}


def resetRepo() {
   onMaster("10m") {
      dir("webapp") {
         sh("git submodule update");
      }
   }
}


// TODO(csilvers): update the slack message with the updated locales.
notify([slack: [channel: '#i18n',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   // Make sure LOCALES was specified -- it's an error not to list a
   // locale to update!
   if (!params.LOCALES) {
      error("You must specify at least one locale to upload!");
   }

   stage("Syncing repos") {
      syncRepos();
   }

   try {
      stage("Running script") {
         runScript();
      }
   } finally {
      // Just to be nice -- it's not essential -- let's reset the repo
      // back to normal.
      stage("Resetting repo") {
         resetRepo();
      }
   }
}
