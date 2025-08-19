// A wrapper around safe_git.sh, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-jobs.
// You should always run kaGit commands from workspace-root.

// We use these user-defined steps from vars/:
//import vars.exec
//import vars.notify
//import vars.withSecrets

// Turn a list of submodules into arguments to pass to safe_git.sh functions.
// Submodules is the empty list (default) for "clone all submodules".
// Submodules is null for "clone no submodules".
// Otherwise it's a list of prefixes; we clone all submodules matching
//     some prefix.
def _submodulesArg(submodules) {
   if (submodules == null) {
      return ['no_submodules'];
   } else {
      return submodules;
   }
}

// Normally we check out repos using our own `safe-git` scripts, but
// those exist in the jenkins-jobs repo so we can't use that script
// for that repo.  This is the command that bootstraps checking out
// jenkins-jobs so we can then check out other repos.
// This is typically not called manually, but instead by onMaster/etc.
def checkoutJenkinsTools() {
   // TODO(csilvers): only do this once per workspace per script.
   dir("jenkins-jobs") {
      // we see a lot of connection timeout to github:433
      // before we figure out the root cause, we use retry {}
      // to tempoary avoid timeout error.
      // TODO(Kai): need to find out the root cause of timeout
       retry {
         git(url: "https://github.com/Khan/jenkins-jobs",
            changelog: false, poll: false);
         // The built-in 'git' step is not great: it doesn't do
         // submodules, and it messes up the tracking with the remote.
         sh("git submodule update --init --recursive");
         sh("git branch --set-upstream-to origin/master master");
       }
   }
}

// Represents a single ref from `git ls-remote ...` command output.
// https://git-scm.com/book/ms/v2/Git-Internals-Git-References
class Ref {
   // Ref name that was returned from ls-remote. Points to hash.
   String name

   // The commit hash (sha1) this ref points to. Also known as commit ID.
   String hash
}

String _refNameFromLsRemoteLine(String lsRemoteLine) {
   return lsRemoteLine.split("\t")[1].trim();
}

String _commitHashFromLsRemoteLine(String lsRemoteLine) {
   return lsRemoteLine.split("\t")[0].trim();
}

// Return each ref that was output by ls-remote for a given commit-ish.
// ls-remote does not return any results when commit hashes are queried
// directly.
//
// Example output: 
// $ git ls-remote origin foo
// 0670bc5a1c0bab364dfb981f7854b6b17bdd49db  refs/heads/deploy/foo
// df49834270ad034ecf776590908d429a8140c485  refs/heads/foo
// 2998fe06daa988757b847afd5a99022334b90d4d  refs/tags/foo
Ref[] lsRemote(String repo, String committish) {
   // https://git-scm.com/docs/git-ls-remote
   String lsRemoteOutput = exec.outputOf(["git", "ls-remote", "-q", repo,
                                          committish]);
   if (lsRemoteOutput == "") {
      return [];
   }
   
   return lsRemoteOutput.split("\n").collect {
      new Ref(hash: _commitHashFromLsRemoteLine(it),
              name: _refNameFromLsRemoteLine(it));
   };
}

// Return commit hash from a ls-remote result if any refs names are an exact
// match to the branch name. A partial match is not performed here because we
// don't want to return the hash for `deploy/foo` when the user requested just
// `foo`.
String _findCommitHashFromBranchRef(Ref[] refs, String branchName) {
   Ref found = refs.find { it.name == "refs/heads/${branchName}" }
   if (found) {
      return found.hash;
   }
   return null;
}

// Return commit hash from a ls-remote result if any ref names are an exact
// match to the tag name. A partial match is not performed here because we don't
// want to return the hash for `deploy/foo` when the user requested just `foo`.
String _findCommitHashFromTagRef(Ref[] refs, String tagName) {
   Ref found = refs.find { it.name == "refs/tags/${tagName}" }
   if (found) {
      return found.hash;
   }
   return null;
}

// Return a commit ID (sha1 hash) from a commit-ish. If a branch or tag name, we
// assume the branch exists on the remote and get the hash from there,
// preferring branches over tags. Otherwise, if the input looks like a hash we
// check for it in origin before returning it verbatim if it exists. Otherwise,
// we error.
// https://git-scm.com/docs/gitglossary#Documentation/gitglossary.txt-commit-ishalsocommittish
String resolveCommitish(String repo, String committish) {
   String hash = null
   stage("Resolving commit-ish") {
      timeout(time: 1, unit: "HOURS") {
         Ref[] lsRemoteRefs = lsRemote(repo, committish);

         // First, check for branch as we prefer branches over tags.
         hash = _findCommitHashFromBranchRef(lsRemoteRefs, committish);
         if (hash) {
            echo("'${committish}' is a branch that resolves to ${hash}");
            return;
         }

         // No branch found, check for tag.
         hash = _findCommitHashFromTagRef(lsRemoteRefs, committish);
         if (hash) {
            echo("'${committish}' is a tag that resolves to ${hash}");
            return;
         }

         // No branch or tag found. If this looks like a sha1 hash already, see
         // if it exists as a commit hash in origin.
         if (committish ==~ /[0-9a-fA-F]{5,}/) {
            echo("'${committish}' looks like a commit hash, " +
                  "checking for it in origin.")
            Integer exitCode = sh(script: "git fetch origin ${committish}", 
                                  returnStatus: true);
            // A 0 exit code means the commit exists in origin.
            if (exitCode == 0) {
               echo("'${committish}' is a commit hash in origin.");
               hash = committish;
               return;
            }
         }
      }
   }

   if (hash) {
      return hash;
   }

   error("Cannot find '${committish}' in repo '${repo}'");
}

def _buildTagFile(repo) {
   // `repo` is something like `git@github.com:Khan/webapp`.
   return "build_tag.${repo.split('/')[-1]}";
}

// true iff the current job had previously synced the given repo to
// the given commit using safeSyncToOrigin.
def wasSyncedTo(repo, commit, syncFn) {
   if (!fileExists(_buildTagFile(repo))) {
      return false;
   }
   try {
      actual = readFile(_buildTagFile(repo));
      return actual == "${syncFn} ${commit} ${env.BUILD_TAG}";
   } catch (e) {
      notify.rethrowIfAborted(e);
      return false;     // build_tag file was just deleted.
   }
}

// Clone a repo, using our existing workdirs if possible.
// This is very low-level, and does not update submodules!  You probably want
// safeSyncToOrigin, below, instead.
def quickClone(repoToClone, directory, commit) {
   exec(["jenkins-jobs/safe_git.sh", "clone", repoToClone, directory, commit]);
}

// Fetch a repo, with locking.
// This is very low-level, and does not update submodules!  You probably want
// safeSyncToOrigin, below, instead.
def quickFetch(directory) {
   exec(["jenkins-jobs/safe_git.sh", "simple_fetch", directory]);
}

// Submodules is as in _submodulesArg.
// Unless `force` is True, we are a noop if that dir is already synced
// to the given commit *by this same jenkins job*.
def safeSyncToOrigin(repoToClone, commit, submodules=[], force=false) {
   if (!force && wasSyncedTo(repoToClone, commit, "safeSyncToOrigin")) {
      return;
   }
   exec(["jenkins-jobs/safe_git.sh", "sync_to_origin", repoToClone, commit] +
        _submodulesArg(submodules));
   writeFile(file: _buildTagFile(repoToClone),
             text: "safeSyncToOrigin ${commit} ${env.BUILD_TAG}");
}

// dir is the directory to run the pull in (can be in a sub-repo)
def safePull(dir) {
   exec(["jenkins-jobs/safe_git.sh", "pull", dir]);
}

// dir is the directory to run the pull in (can be in a sub-repo)
// branch is the branch to pull.  Submodules is as in _submodulesArg`.
def safePullInBranch(dir, branch, submodules=[]) {
   exec(["jenkins-jobs/safe_git.sh", "pull_in_branch", dir, branch] +
        _submodulesArg(submodules));
}

// dir is the directory to commit in (*cannot* be a submodule).
// args are the arguments to git commit.
def safeCommitAndPush(dir, args) {
   // Automatic commits from jenkins don't need a test plan.
   withEnv(["FORCE_COMMIT=1"]) {
      exec(["jenkins-jobs/safe_git.sh", "commit_and_push", dir] + args);
   }
}

// repoDir is the root of the repo that holds the submodule.
// submoduleDir is the dir of the submodule, relative to repodDir.
// args are the arguments to git commit.
def safeCommitAndPushSubmodule(repoDir, submoduleDir, args) {
   // Automatic commits from jenkins don't need a test plan.
   withEnv(["FORCE_COMMIT=1"]) {
      exec(["jenkins-jobs/safe_git.sh", "commit_and_push_submodule",
            repoDir, submoduleDir] + args);
   }
}

// repoDir is the root of the repo that holds the submodule.
// submoduleDir is the dir of the submodule, relative to repoDir.
def safeUpdateSubmodulePointerToMaster(repoDir, submoduleDir) {
    // Automatic commits from jenkins don't need a test plan.
    withEnv(["FORCE_COMMIT=1"]) {
        exec(["jenkins-jobs/safe_git.sh", "update_submodule_pointer_to_master",
              repoDir, submoduleDir]);
    }
}

// Submodules is as in _submodulesArg`.
def safeMergeFromBranch(dir, commitToMergeInto, branchToMerge, submodules=[]) {
   // This job talks directly to slack on error (for better or worse),
   // so it needs that secret.
   withSecrets.slackAlertlibOnly() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_branch",
            dir, commitToMergeInto, branchToMerge]
           + _submodulesArg(submodules));
   }
}


// Submodules is as in _submodulesArg`.
def safeMergeFromMaster(dir, commitToMergeInto, submodules=[]) {
   // This job talks directly to slack on error (for better or worse),
   // so it needs that secret.
   withSecrets.slackAlertlibOnly() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_master",
            dir, commitToMergeInto] + _submodulesArg(submodules));
   }
}


// Merge multiple webapp git revisions together and return the resulting commit
// SHA.
//
// Arguments:
// - gitRevisions: String containing one or more branch name, tag name, or
//   commit SHA separated by "+".
// - tagName: String to tag the resulting commit with. We need to tag the result
//   of the merge so git doesn't prune it.
// - description: Human-readable description to aid in debugging. Added to new
//   commit message.
//
// Notes:
// - Used by merge-granches.groovy and deploy-znd.groovy.
String mergeRevisions(gitRevisions, tagName, description) {
   List<String> allRevisions = gitRevisions.split(/\+/);

   // Trim passed revisions for consistent ouput formatting.
   for (Integer i = 0; i < allRevisions.size(); i++) {
      allRevisions[i] = allRevisions[i].trim();
   }

   quickClone("git@github.com:Khan/webapp", "webapp", allRevisions[0]);
   dir('webapp') {
      // We need to reset before fetching, because if a previous incomplete
      // merge left .gitmodules in a weird state, git will fail to read its
      // config, and even the fetch can fail.  This also avoids certain
      // post-merge-conflict states where git checkout -f doesn't reset as much
      // as you might think.
      exec(["git", "reset", "--hard"]);
   }

   // Get rid of all old branches; if they were dangling they'd break fetch.
   exec(["jenkins-jobs/safe_git.sh", "clean_branches", "webapp"]);
   quickFetch("webapp");

   // Do the merge(s)!
   dir('webapp') {
      for (Integer i = 0; i < allRevisions.size(); i++) {
         String branchSha1 = resolveCommitish("git@github.com:Khan/webapp",
                                              allRevisions[i]);
         if (i == 0) {
            // First, checkout the base revision. Even if there aren't
            // subsequent revisions to merge, we still want the correct revision
            // checked out after we return.
            try {
               // Note that this is a no-op when we did a fresh clone above.
               ExecResult result = exec.runCommand([
                  "git", "checkout", "-f", branchSha1
               ]);
               if (result.exitCode != 0) {
                  echo "Checkout failure command: ${result.command}";
                  echo "Checkout failure exitCode: ${result.exitCode}";
                  echo "Checkout failure ouput: ${result.output}";
                  notify.fail("Failed to checkout ${branchSha1}:\n" +
                           "${result.output}");
               }
            } catch(FailedBuild e) {
               // Error from git checkout thrown by notify.fail().
               throw e;
            } catch (e) {
               // Error from inability to call git checkout.
               notify.rethrowIfAborted(e);
               notify.fail("Failed to call checkout ${branchSha1}: " +
                           "${e.getMessage()}", e);
            }
         } else {
            // Then, merge the next successive revision.

            // Write our own commit message so it's not just the SHA.
            String previousRevisions = allRevisions[0..<i].join(' + ');
            String commitMessage = [
               "Merge '${allRevisions[i]}' into '${previousRevisions}' " +
                  "for tag '${tagName}'",
               "",
               "${description}"
            ].join("\n");

            try {
               ExecResult result = exec.runCommand([
                  "git", "merge", branchSha1, "-m", commitMessage
               ]);
               if (result.exitCode != 0) {
                  echo "Merge failure command: ${result.command}";
                  echo "Merge failure exitCode: ${result.exitCode}";
                  echo "Merge failure ouput: ${result.output}";
                  notify.fail("Failed to merge ${branchSha1} into " +
                              "${previousRevisions}:\n${result.output}");
               }
            } catch(FailedBuild e) {
               // Error from git merge thrown by notify.fail().
               throw e;
            } catch (e) {
               // Error from inability to call git merge.
               notify.rethrowIfAborted(e);
               notify.fail("Failed to call merge ${branchSha1} into " +
                           "${previousRevisions}: ${e.getMessage()}", e);
            }
         }
      }

      // We need to at least tag the commit, otherwise github may prune it.
      // (We can skip this step if something already points to the commit.)
      // These tags ar pruned weekly in weekly-maintenance.sh.
      if (exec.outputOf(["git", "tag", "--points-at", "HEAD"]) == "" &&
          exec.outputOf(["git", "branch", "-r", "--points-at", "HEAD"]) == "") {
         exec(["git", "tag", tagName, "HEAD"]);
         exec(["git", "push", "--tags", "origin"]);
      }
      String sha1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
      echo("Resolved ${gitRevisions} --> ${sha1}");
      return sha1;
   }
}
