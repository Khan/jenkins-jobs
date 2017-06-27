//import vars.exec

// This is no longer where we store secrets.py, it's just where the
// password lives.
def _secretsPasswordDir() {
   return "${env.HOME}/secrets_py";
}


// This must be called from workspace-root.
def call(Closure body) {
   try {
      // First, set up secrets.
      // This decryption command was modified from the make target
      // "secrets_decrypt" in the webapp project.
      exec(["openssl", "cast5-cbc", "-d",
            "-in", "webapp/shared/secrets.py.cast5",
            "-out", "webapp/shared/secrets.py",
            "-kfile", "${_secretsPasswordDir()}/secrets.py.cast5.password"]);
      sh("chmod 600 webapp/shared/secrets.py");

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=webapp/shared"]){
         body();
      }
   } finally {
      // Finally, clean up secrets.py so if the next job intends
      // to run without secrets, it does.
      sh("rm -f webapp/shared/secrets.py");
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
