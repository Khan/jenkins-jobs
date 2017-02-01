def call(Closure body) {
   // We only care about the 'decrypt' part here, not 'add to pythonpath'.
   sh("./jenkins-tools/build.lib decrypt_secrets_py_and_add_to_pythonpath");
   def secretsDir = sh(script: ('. ./jenkins-tools/build.lib; ' +
                                'echo $SECRETS_DIR'),
                       returnStdout: true).trim();
   withEnv(["PYTHONPATH=${secretsDir}:${env.PYTHONPATH}"]) {
      body();
   }
}
