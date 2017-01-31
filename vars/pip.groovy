def call(shellCommand, installSecrets=false, workspaceRoot="..") {
   sh ("pushd '${workspaceRoot}'; " +
       ". ./jenkins-tools/build.lib; " +
       "ensure_virtualenv; " +
       "[ '${installSecrets}' = 'true' ] && decrypt_secrets_py_and_add_to_pythonpath; " +
       "popd; " +
       "${shellCommand}");
}
