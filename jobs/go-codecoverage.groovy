// The pipeline job for generating code coverage of all Go code.
//
// This will run coverage on Go code in /services and /pkg, and publish the
// results to the Jenkins coverage plugin.
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
    withTimeout('5m') {
        // We want to make sure all nodes below work at the same sha1,
        // so we resolve our input commit to a sha1 right away.
        GIT_SHA1 = kaGit.resolveCommittish("git@github.com:Khan/webapp",
                                           params.GIT_REVISION);
    }
}

def cloneRepo() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);
    // these lines are needed so the source code can be copied and associated
    // with the reports
    sh 'mkdir -p /home/ubuntu/go/src/github.com && mkdir -p /home/ubuntu/go/src/github.com/Khan'
    sh 'ln -s "$(pwd)/webapp" "/home/ubuntu/go/src/github.com/Khan/webapp" || true'
}

// Install app dependencies.
def installDeps(){
    dir(WEBAPP_DIR) {
        sh 'go mod download'
   }
}

def runTests(){
    dir(WEBAPP_DIR) {
        // sometimes an individual test will fail, but we want to continue
        // and generate the report anyway
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            // A single `go test` gives us the same cross-package attribution
            // go-acc did -- a test in one package still credits the lines it
            // exercises in another, so resolver tests count towards the domain
            // funcs they call -- because that is all -coverpkg ever meant.
            // What it drops is go-acc's cost model: go-acc shelled out to `go
            // test` once per package and waited for each in turn, so every
            // package's tests ran strictly one at a time, each re-resolving a
            // package graph built from a 50KB -coverpkg list.  One invocation
            // loads that graph once and runs package tests concurrently, up to
            // -p (default GOMAXPROCS).
            //
            // We measure a narrower set than we run:
            //
            //  - We don't measure binaries.  A coverage figure for `func
            //    main()` tells nobody anything, so package main is out.
            //  - We do still run their tests.  A binary's tests exercise the
            //    libraries underneath it, and that coverage counts.
            //  - We run packages with no test files of their own, too.  A
            //    package only reaches the profile if it gets linked into some
            //    test binary, so leaving those out would quietly drop
            //    uncovered code from the denominator instead of reporting it
            //    at 0%.
            sh('''#!/bin/bash
                # pipefail matters: we pipe `go test` through sed below, and
                # without it sed's exit status would mask a test failure and
                # the stage would look green.
                set -eo pipefail
                go list -f '{{.ImportPath}} {{.Name}}' ./services/... ./pkg/... \\
                    | awk '$1 !~ /testutil|generated/' \\
                    > packages.txt
                awk '{print $1}' packages.txt > test-packages.txt
                awk '$2 != "main" {print $1}' packages.txt > coverage-packages.txt

                # Every `ok` line ends with `coverage: N% of statements in <every
                # package in -coverpkg>`.  At 900+ packages that is a ~50KB
                # line per test binary -- tens of MB of console log once
                # `timestamps {}` has wrapped each one.  The per-binary figure
                # is near-meaningless anyway (each binary covers its own slice
                # of the whole list); the real number comes from the merged
                # profile.  Keep the percentage, drop the list.
                go test \\
                    -covermode=atomic \\
                    -coverpkg="$(paste -sd, coverage-packages.txt)" \\
                    -coverprofile=coverage.txt \\
                    -timeout=30m \\
                    $(cat test-packages.txt) \\
                  | sed 's/ of statements in .*/ of statements/'
            ''')
        }
    }
}

def publishCoverage() {
    dir(WEBAPP_DIR) {
        // -coverpkg instruments every listed package into every test binary
        // that links it, and each binary writes counters for all of them on
        // exit -- so the merged profile is O(binaries x packages), about
        // 0.7GB here with ~57x of it redundant.  Summing duplicate blocks is
        // what `go tool cover` does internally anyway, so doing it once here
        // is lossless and saves every later consumer from re-reading the full
        // file.  Done in this stage, not after `go test`, so it still happens
        // when a test failure trips the catchError above.
        sh('''
            set -e
            awk 'NR==1{print;next} {k=$1" "$2; c[k]+=$3} END{for(x in c) print x, c[x]}' \\
                coverage.txt | { read -r hdr; echo "$hdr"; sort; } > coverage-merged.txt
            mv coverage-merged.txt coverage.txt
        ''')
        // A per-function dump in the build log, handy for working out which
        // package moved the trend line.
        sh 'go tool cover -func coverage.txt'
        // The Coverage plugin parses `go test -coverprofile` output natively
        // via its GO_COV parser, so there is no conversion step and no
        // intermediate XML.  (The old code-coverage-api plugin's
        // publishCoverage/coberturaAdapter steps are deprecated.)
        //
        // This gives line coverage only.  A go coverage profile records which
        // statements ran and nothing else, so neither branch coverage nor
        // cyclomatic complexity can come out of it -- reporting those means
        // recomputing them from source and converting to a format whose
        // schema has fields for them, which is not worth the dependency.
        recordCoverage(
            tools: [[parser: 'GO_COV', pattern: 'coverage.txt']],
            // webapp is about 1GB, so keep only the last build's sources.
            sourceCodeRetention: 'LAST_BUILD',
        )
        sh 'rm -f coverage.txt packages.txt test-packages.txt coverage-packages.txt'
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
    stage('publish coverage') {
        publishCoverage();
    }
}
