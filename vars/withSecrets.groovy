//import vars.exec

// This is no longer where we store secrets.py, it's just where the
// password lives.
_SECRETS_PASSWORD_DIR = "${env.HOME}/secrets_py";

// The number of active withSecrets blocks.  We only want to clean secrets up
// at the end if we are exiting the last withSecrets block, since they likely
// all share a workspace.
// TODO(benkraft): In principle this should be per-directory; in practice
// assuming we're always in the same directory is good enough at present.
// TODO(benkraft): Make sure updates to this are actually atomic.
_activeSecretsBlocks = 0;


// This must be called from workspace-root.
def call(Closure body) {
   try {
      // If this runs before the move of secrets.py is deployed,
      // we need to operate on the old secrets file.
      // TODO(benkraft): remove after the move is deployed, by
      // 15 June 2017.
      if (fileExists("webapp/shared/secrets.py.cast5")) {
         webappSecretsDir = "webapp/shared";
      } else {
         webappSecretsDir = "webapp";
      }

      // First, set up secrets.
      // This decryption command was modified from the make target
      // "secrets_decrypt" in the webapp project.
      // Note that we do this even if ACTIVE_SECRETS_BLOCKS > 0;
      // secrets.py.cast5 might have changed.
      exec(["openssl", "cast5-cbc", "-d",
            "-in", "${webappSecretsDir}/secrets.py.cast5",
            "-out", "${webappSecretsDir}/secrets.py",
            "-kfile", "${_SECRETS_PASSWORD_DIR}/secrets.py.cast5.password"]);
      sh("chmod 600 ${webappSecretsDir}/secrets.py");
      _activeSecretsBlocks++;

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=${webappSecretsDir}"]){
         body();
      }
   } finally {
      _activeSecretsBlocks--;
      // Finally, iff we're exiting the last withSecrets block, clean up
      // secrets.py so if the next job intends to run without secrets, it does.
      // We remove both versions just in case an old one was floating around.
      if (!activeSecretsBlocks) {
         sh("rm -f webapp/shared/secrets.py");
         sh("rm -f webapp/secrets.py");
      }
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
