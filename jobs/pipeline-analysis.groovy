// Runs the analysis of a jenkins pipeline

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.notify
//import vars.withTimeout
//import vars.onMaster

new Setup(steps
).addStringParam(
        "PROJECT",
        """Set this to the project you wish to analyze. Ignored if this job is
triggered by the completion of another job.""",
        ""
).addStringParam(
        "BUILD_NUMBER",
        """Set this to the build number you wish to analyze. Ignored if this
job is triggered by the completion of another job.""",
        ""
).apply();


def runAnalysis() {
    withTimeout('1h') {
        def upstreamProject = params.PROJECT
        def upstreamBuild = params.BUILD_NUMBER

        def upstream = currentBuild.rawBuild.getCause(hudson.model.Cause$UpstreamCause)

        if upstream is not None {
            upstreamProject = upstream.upstreamProject
            upstreamBuild = upstream.upstreamBuild
        }

        echo "Analyzing ${upstreamProject} - ${upstreamBuild}"

        //TODO(dbraley): Actually call the thing
    }
}

// TODO(dbraley): This should run probably run on a worker, but from which pool?
onMaster('1h') {
   notify([slack: [channel: '#infrastructure-devops',
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure-devops',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
 
        stage("Sync Analysis Repo") {
            // do nothing for now
        }

        stage("Running Analysis") {
            runAnalysis()
        }
 
        // Not sure if we need a follow up stage here
    }
}
