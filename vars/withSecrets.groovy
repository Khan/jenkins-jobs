import groovy.transform.Field
//import vars.exec

// The number of active withSecrets blocks.  We only want to clean secrets up
// at the end if we are exiting the last withSecrets block, since they likely
// all share a workspace.
// TODO(benkraft): In principle this should be per-directory; in practice
// assuming we're always in the same directory is good enough at present.
// TODO(benkraft): Make sure updates to this are actually atomic.
@Field _activeSecretsBlocks = 0;

def _secretsPasswordPath() {
   return "${env.HOME}/secrets_py/secrets.py.aes.password";
}

def _withSecrets(Closure body) {
   try {
      // First, set up secrets.
      // This decryption command was modified from the make target
      // "secrets_decrypt" in the webapp project.
      // Note that we do this even if ACTIVE_SECRETS_BLOCKS > 0;
      // secrets.py.aes might have changed.
      exec(["openssl", "aes-256-cbc", "-d", "-md", "sha256", "-salt",
            "-in", "webapp/shared/secrets.py.aes",
            "-out", "webapp/shared/secrets.py",
            "-kfile", _secretsPasswordPath()]);
      sh("chmod 600 webapp/shared/secrets.py");
      _activeSecretsBlocks++;

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=${pwd()}/webapp/shared"]) {
         body();
      }
   } finally {
      _activeSecretsBlocks--;
      // Finally, iff we're exiting the last withSecrets block, clean up
      // secrets.py so if the next job intends to run without secrets, it does.
      if (!_activeSecretsBlocks) {
         sh("rm -f webapp/shared/secrets.py webapp/shared/secrets.pyc");
      }
   }
}


// This must be called from workspace-root.
def call(Closure body) {
   _withSecrets(body);
}

def slackAlertlibOnly(Closure body) {
   // TODO(csilvers): provide another implementation that just gets the
   // one slack secret from GCS.  But properly promote from this to a
   // "real" withSecrets call later.
   _withSecrets(body);
}

// Only try to decrypt secrets if webapp is checked out
// and secrets are present.
// Use cautiously! -- this may not decrypt secrets for you.
def ifAvailable(Closure body) {
   if (fileExists("webapp/shared/secrets.py.aes")) {
      call(body);
   }
}
