// This job generates topic tree json for all locales and writes the 
// generated files to GCS which are used by api '/api/v1/topictree'
// for its response.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """The name of a webapp branch to use when running generate topictree 
	json script. Most of the time master (the default) is the correct choice.
	The main reason to use a different branch is to test changes to the  
	generate topictree json process that haven't yet been merged to master.""",
    "master"

).addStringParam(
        "LOCALE",
	    """The locale for which to run this job for.""",
	    ""
).addCronSchedule("H H * * *"

).apply();

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);

    dir("webapp") {
        sh("make clean_pyc");    // in case some .py files went away
        sh("make deps");
    }

    def inLocale = params.LOCALE;
    if (!inLocale) {
        // Run for all locales that are test or better.
		def locales = ['bg', 'bn', 'cs', 'da', 'de', 'es', 'fr', 'gu', 'hi', 'hy',
					   'id', 'it', 'ja', 'ka', 'ko', 'mn', 'nb', 'nl', 'pl', 'pt', 'pt-pt',
					   'sr', 'sv', 'ta', 'tr', 'zh-hans'];
        for (locale in locales) {
            println("Invoking script to generate topictree json for locale: ${locale}");
			lock("using-a-lot-of-memory") {
				withSecrets() {
					sh("jenkins-jobs/run-topictree-gen.sh ${locale}");
				}
			}
        }
    } else {
        lock("using-a-lot-of-memory") {
            withSecrets() {
                sh("jenkins-jobs/run-topictree-gen.sh ${inLocale}");
            }
        }
    }
}

onMaster('6h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}

