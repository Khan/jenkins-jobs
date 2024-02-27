// This activates the top-level ("deploy") virtualenv, which allows
// us to run the python3 scripts used for deploying.
// This must be called from workspace-root.
def python3(Closure body) {
   echo("Activating python3 virtualenv");
   sh("make -C webapp/deploy deps");
   withEnv(["PATH=${pwd()}/webapp/genfiles/deploy/venv/bin:${env.PATH}"]) {
      body();
   }
}
