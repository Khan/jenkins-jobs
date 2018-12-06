//import vars.notify

// Clean out a repo before doing work in it.  If the repo
// has a Makefile that defines `make clean` and `make allclean` then
// we use those for the "all" and "most" steps, otherwise we just use
// `git clean` for those two steps.
// This should be done under a node and in the repo you want to clean.
// howClean: how much to clean: all, most, some, none
def call(howClean) {
   if (howClean == "all") {
      sh("if grep -w allclean Makefile >/dev/null 2>&1; then " +
         "    make allclean; " +
         "else" +
         "    git clean -qffdx; git submodule foreach git clean -qffdx; " +
         "fi");

   } else if (howClean == "most") {
      sh("if grep -w clean Makefile >dev/null 2>&1; then make clean; fi");
      // Be a bit more aggressive: delete un-git-added files, for instance.
      sh("git clean -qffdx --exclude genfiles --exclude genwebpack " +
         "--exclude node_modules --exclude third_party/vendored");
      sh("git submodule foreach git clean -qffdx --exclude node_modules");

   } else if (howClean == "some") {
      sh("git clean -qffdx --exclude genfiles --exclude genwebpack " +
         "--exclude node_modules --exclude third_party/vendored");
      sh("git submodule foreach git clean -qffdx --exclude node_modules");
      // genfiles is excluded from "git clean" so we need to manually
      // remove artifacts that should not be kept across builds.
      sh("rm -rf genfiles/test-reports genfiles/lint_errors.txt");

   } else if (howClean == "none") {
      // No need to do anything!

   } else {
      notify.fail("Unknown value for CLEAN: '${howClean}'");

   }
}
