//import vars.exec

def secretsDir() {
   return "${env.HOME}/secrets_py";
}


// This must be called from workspace-root.
def call(Closure body) {
   // We enforce, here, the invariant that, inside secretsDir, the
   // current secrets.py is always the decrypted version of the
   // current secrets.py.cast5 if it exists.  If we can't enforce that
   // we delete secrets.py.cast5 (thus trivially restoring the
   // invariant).  That way, we know that if secrets.py.cast5 hasn't
   // changed since the last call, there's nothing we need to do.
   // TODO(csilvers): there's a race condition here if multiple jobs
   // (or threads within a job) call this at the same time.
   rc = exec.statusOf(["cmp", "webapp/shared/secrets.py.cast5",
                       "${secretsDir()}/secrets.py.cast5"]);
   if (rc != 0) {   // means there are new secrets we need to decrypt
      try {
         exec(["cp", "webapp/shared/secrets.py.cast5", secretsDir()]);
         dir(secretsDir()) {
            // This decryption command was modified from the make target
            // "secrets_decrypt" in the webapp project.
            sh("openssl cast5-cbc -d -in secrets.py.cast5 -out secrets.py " +
               "-kfile secrets.py.cast5.password");
            sh("chmod 600 secrets.py");
         }
      } catch (e) {
         echo("Error decrypting secrets: ${e}.  Deleting the cast5 file.");
         exec(["rm", "-f", "${secretsDir()}/secrets.py.cast5"]);
      }
   }
   withEnv(["PYTHONPATH=${secretsDir()}:${env.PYTHONPATH}"]) {
      body();
   }
}


// Only try to decrypt secrets if webapp is checked out
// and secrets are present.
// Use cautiously! -- this may not decrypt secrets for you.
def ifAvailable(Closure body) {
   if (fileExists("webapp/shared/secrets.py.cast5")) {
      call(body);
   }
}
