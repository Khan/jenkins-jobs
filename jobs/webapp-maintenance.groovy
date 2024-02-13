// Pipeline job that does weekly maintenance on our webapp repo, other
// repos, the jenkins machine itself, etc.
// Tasks include things like:
//    * compressing all png and svg images
//    * cleaning out old docker containers
//    * deleting obsolete translations files
// etc.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withVirtualenv


new Setup(steps

).addStringParam(
    "JOBS",
    """Comma-separated list of functions from weekly-maintenance.sh to run.
    Each value should be a function-name.  If empty, run all functions.""",
    ""

).addCronSchedule("H H * * 0"

).apply();


def runScript() {
   withTimeout('9h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // We do our work in the 'automated-commits' branch.
      kaGit.safePullInBranch("webapp", "automated-commits");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "automated-commits");

      // Do some more cleaning in case the merge modified .gitignore.
      // We run it twice because sometimes we need two runs to clean
      // fully (if the first run deletes a .gitignore in a subrepo, say).
      dir("webapp") {
         sh("timeout 2m git clean -ffd");
         sh("timeout 1m git clean -ffd");
      }

      sh("jenkins-jobs/weekly-maintenance.sh");
   }
}


onMaster('10h') {
   notify([slack: [channel: '#infrastructure',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           email: [to: 'jenkins-admin+builds, csilvers',
                   when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
      def jobs = [];
      stage("Listing jobs to run") {
         if (params.JOBS) {
             jobs = params.JOBS.split(",");
         } else {
             def job_str = exec.outputOf(["jenkins-jobs/weekly-maintenance.sh", "-l"]);
             echo("Running these jobs:\n${job_str}");
             jobs = job_str.split("\n");
         }
      }

      def failed_jobs = [];
      for (def i = 0; i < jobs.size(); i++) {
         stage(jobs[i]) {
            withVirtualenv.python3() {
               catchError(buildResult: "FAILURE", stageResult: "FAILURE",
                          message: "${jobs[i]} failed") {
                  exec(["timeout", "10h", "jenkins-jobs/weekly-maintenance.sh",
                        jobs[i]]);
               }
            }
         }
      }
   }
}
