def call(shellCommand, installSecrets=false, workspaceRoot="..") {
   withEnv(["WORKSPACE_ROOT=${workspaceRoot}"]) {
      sh (". $WORKSPACE_ROOT/jenkins-tools/build.lib; " +
          "ensure_virtualenv; " +
          "[ '${installSecrets}' = 'true' ] && decrypt_secrets_py_and_add_to_pythonpath; " +
          "${shellCommand}");
   }
}
