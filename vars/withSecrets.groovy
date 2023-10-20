import groovy.transform.Field
//import vars.exec

// The number of active withSecrets blocks.  We only want to clean secrets up
// at the end if we are exiting the last withSecrets block, since they likely
// all share a workspace.
// TODO(benkraft): In principle this should be per-directory; in practice
// assuming we're always in the same directory is good enough at present.
// TODO(benkraft): Make sure updates to this are actually atomic.
@Field _activeSecretsBlocks = 0;
@Field _activeSlackSecretsBlocks = 0;
@Field _activeSlackAndStackdriverSecretsBlocks = 0;
@Field _activeGithubSecretsBlocks = 0;

def _secretsPasswordPath() {
   return "${env.HOME}/secrets_py/secrets.py.aes.password";
}

// This must be called from workspace-root.
def call(Closure body) {
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

// This must be called from workspace-root.  While this is in scope,
// *only* the slack secret is available, even if there's a higher-up
// call to withSecrets().
def slackAlertlibOnly(Closure body) {
   try {
      sh("mkdir -p decrypted_secrets/slack/");
      sh("gcloud --project khan-academy secrets versions access latest --secret Slack__API_token_for_alertlib >decrypted_secrets/slack/secrets.py.tmp");
      sh("perl -pli -e 's/^/slack_alertlib_api_token = \"/; s/\$/\"/' decrypted_secrets/slack/secrets.py.tmp");
      sh("rm -f decrypted_secrets/slack/secrets.py");
      // Create this file atomically.
      mv("decrypted_secrets/slack/secrets.py.tmp decrypted_secrets/slack/secrets.py");
      sh("chmod 600 decrypted_secrets/slack/secrets.py");
      _activeSlackSecretsBlocks++;

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=${pwd()}/decrypted_secrets/slack"]) {
         body();
      }
   } finally {
      _activeSlackSecretsBlocks--;
      // Finally, iff we're exiting the last slackAlertlibOnly block,
      // clean up our secret.
      if (!_activeSlackSecretsBlocks) {
         sh("rm -rf decrypted_secrets/slack/");
      }
   }
}

// This must be called from workspace-root.  While this is in scope,
// *only* the github secrets are available, even if there's a higher-up
// call to withSecrets().
def githubAlertlibOnly(Closure body) {
   try {
      sh("mkdir -p decrypted_secrets/github/");
      sh("gcloud --project khan-academy secrets versions access latest --secret khan_actions_bot_github_personal_access_token__Repository_Status___Deployments__repo_status__repo_deployment__ >decrypted_secrets/github/secrets.py.tmp");
      sh("perl -pli -e 's/^/github_repo_status_deployment_pat = \"/; s/\$/\"/' decrypted_secrets/github/secrets.py.tmp");
      sh("rm -f decrypted_secrets/github/secrets.py");
      // Create this file atomically.
      mv("decrypted_secrets/github/secrets.py.tmp decrypted_secrets/github/secrets.py");
      sh("chmod 600 decrypted_secrets/github/secrets.py");
      _activeGithubSecretsBlocks++;

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=${pwd()}/decrypted_secrets/github"]) {
         body();
      }
   } finally {
      _activeGithubSecretsBlocks--;
      // Finally, iff we're exiting the last githubAlertlibOnly block,
      // clean up our secret.
      if (!_activeGithubSecretsBlocks) {
         sh("rm -rf decrypted_secrets/github/");
      }
   }
}

// This must be called from workspace-root.  While this is in scope,
// *only* the slack secret is available, even if there's a higher-up
// call to withSecrets().
def slackAndStackdriverAlertlibOnly(Closure body) {
   try {
      sh("mkdir -p decrypted_secrets/slack_and_stackdriver/");
      sh("gcloud --project khan-academy secrets versions access latest --secret Slack__API_token_for_alertlib >decrypted_secrets/slack_and_stackdriver/secrets.py.tmp");
      sh("perl -pli -e 's/^/slack_alertlib_api_token = \"/; s/\$/\"/' decrypted_secrets/slack_and_stackdriver/secrets.py.tmp");
      sh("echo google_alertlib_service_account = \\'\\'\\' >>decrypted_secrets/slack_and_stackdriver/secrets.py.tmp");
      sh("gcloud --project khan-academy secrets versions access latest --secret google_api_service_account__for_alertlib_ >>decrypted_secrets/slack_and_stackdriver/secrets.py.tmp");
      sh("echo \\'\\'\\' >>decrypted_secrets/slack_and_stackdriver/secrets.py.tmp");
      sh("rm -f decrypted_secrets/slack_and_stackdriver/secrets.py");
      // Create this file atomically.
      mv("decrypted_secrets/slack_and_stackdriver/secrets.py.tmp decrypted_secrets/slack_and_stackdriver/secrets.py");
      sh("chmod 600 decrypted_secrets/slack_and_stackdriver/secrets.py");
      _activeSlackAndStackdriverSecretsBlocks++;

      // Then, tell alertlib where secrets live, and run the wrapped block.
      withEnv(["ALERTLIB_SECRETS_DIR=${pwd()}/decrypted_secrets/slack_and_stackdriver"]) {
         body();
      }
   } finally {
      _activeSlackAndStackdriverSecretsBlocks--;
      // Finally, iff we're exiting the last slackAlertlibOnly block,
      // clean up our secret.
      if (!_activeSlackAndStackdriverSecretsBlocks) {
         sh("rm -rf decrypted_secrets/slack_and_stackdriver/");
      }
   }
}

