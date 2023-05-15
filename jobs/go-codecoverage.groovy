// The pipeline job for generating code coverage of all Go code.
//
// This will run coverage on Go code in /services and /pkg, and output a
// cobertura report compatible with the jenkins cobertura plugin.
//
// This is ran periodically, and not as part of a deploy because it runs on all
// of the code, and takes a long time. It's useful (but not critical) to have
// this information available so teams can identify areas of code lacking
// unit test coverage.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

new Setup(steps
).allowConcurrentBuilds(
// This will run weekly so it's safe to keep a few builds around so we can
// see a trend line
).resetNumBuildsToKeep(
   15,
).addStringParam(
   "GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.""",
   "master"
).apply()

// We're running all the tests, so we need the big worker
WORKER_TYPE = 'big-test-worker'
WEBAPP_DIR = 'webapp'
// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;

def initializeGlobals() {
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}

def cloneRepo() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);
    // these lines are needed so the source code can be copied and associated
    // with the reports
    sh 'mkdir -p /home/ubuntu/go/src/github.com && mkdir -p /home/ubuntu/go/src/github.com/Khan'
    sh 'ln -s "$(pwd)/webapp" "/home/ubuntu/go/src/github.com/Khan/webapp" || true'
}

// install app dependencies as well as the gocover-cobertura plugin which
// allows us to generate slick coverage reports
def installDeps(){
    dir(WEBAPP_DIR) {
        // converts native go coverage to cobertura format, do not use the
        // t-yuki version, it's buggy
        sh 'go get github.com/boumenot/gocover-cobertura'
        sh 'go install github.com/ory/go-acc@latest'
        sh 'go mod download'
   }
}

def runTests(){
    dir(WEBAPP_DIR) {
        // sometimes an individual test will fail, but we want to continue
        // and generate the report anyway
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            // | grep -v, will remove packages we don't want tested, like
            // generated dirs
            // go-acc is used to get correct coverage which would include
            // integration tests (resolvers which call domain funcs)
            sh 'go-acc ./services/... --ignore=testutil,generated,cmd -o=coverage.txt'
        }
    }
}

def generateCoverageXML() {
    dir(WEBAPP_DIR) {
        sh 'rm coverage.xml || true'
        sh 'go tool cover -func coverage.txt'
        sh 'go run github.com/boumenot/gocover-cobertura < coverage.txt > coverage.xml'
        sh 'rm coverage.txt'
    }
}

def buildCoberturaReport() {
    dir(WEBAPP_DIR) {
        // coberturaAdapter works well, but cobertura doesn't see supported formats
        // here: https://www.jenkins.io/doc/pipeline/steps/code-coverage-api/
        // STORE_LAST_BUILD instead of all because webapp is about 1GB
        publishCoverage adapters: [coberturaAdapter(path:"coverage.xml")], sourceFileResolver: sourceFiles('STORE_LAST_BUILD')
    }
}


onWorker(WORKER_TYPE, '5h') {   // timeout
    initializeGlobals();

    stage('clone repo') {
        cloneRepo();
    }
    stage('install deps') {
        installDeps();
    }
    stage('run tests') {
        runTests();
    }
    stage("generate coverage xml") {
        generateCoverageXML();
    }
    stage ("build cobertura report") {
        buildCoberturaReport();
    }
}
