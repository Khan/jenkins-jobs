// This must be called from a directory that has jenkins-tools checked
// out as a subdir.
def call(Closure body) {
    // `ensure_virtualenv` creates an `env` subdir with the virtualenv in it.
    sh("./jenkins-tools/build.lib ensure_virtualenv");
    def virtualenvDir = sh(script: "cd env && pwd", returnStdout: true).trim();
    withEnv(["VIRTUAL_ENV=${virtualenvDir}",
             "PATH=${virtualenvDir}/bin:${env.PATH}"]) {
       body();
    }
}
