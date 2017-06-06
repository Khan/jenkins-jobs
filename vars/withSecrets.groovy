//import vars.exec

def secretsDir() {
   return "${env.HOME}/secrets_py";
}


// This must be called from workspace-root.
def call(Closure body) {
   exec(["cp", "webapp/secrets.py.cast5", secretsDir()]);
   dir(secretsDir()) {
      // This decryption command was copied from the make target
      // "secrets_decrypt" in the webapp project.
      sh("openssl cast5-cbc -d -in secrets.py.cast5 -out secrets.py " +
         "-kfile secrets.py.cast5.password");
      sh("chmod 600 secrets.py");
   }
   withEnv(["PYTHONPATH=${secretsDir()}:${env.PYTHONPATH}"]) {
      body();
   }
}


// Only try to decrypt secrets if webapp is checked out
// and secrets are present.
// Use cautiously! -- this may not decrypt secrets for you.
def ifAvailable(Closure body) {
   if (fileExists("webapp/secrets.py.cast5")) {
      call(body);
   }
}
