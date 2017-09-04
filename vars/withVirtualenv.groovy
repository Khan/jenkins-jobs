//import vars.exec

// This must be called from workspace-root.
def call(Closure body) {
   if (env.VIRTUAL_ENV && fileExists(env.VIRTUAL_ENV)) {
      // we're already in a virtualenv.  The fileExists is necessary
      // because a node can inherit the environment from its parent
      // and have an inaccuarte VIRTUAL_ENV.
      echo("(Not activating virtualenv; already active at ${env.VIRTUAL_ENV})");
      body();
      return;
   }
   if (!fileExists("env")) {
      echo("Creating new virtualenv(s)");

      // We create a "normal" virtualenv we use most of the time, and
      // a "dbg" virtualenv that uses python2.7-dbg and lets us debug
      // running python processes using gdb.
      sh("virtualenv --python=python env.normal");
      if (exec.statusOf(["which", "python2.7-dbg"]) == 0) {
         sh("virtualenv --python=python2.7-dbg env.dbg");
         // Need one more fix, as per
         // http://stackoverflow.com/questions/22931774/how-to-use-gdb-python-debugging-extension-inside-virtualenv
         sh("cp -a /usr/lib/debug/usr/bin/python*gdb.py env.dbg/bin/");
      }
      // Have 'env' point to 'env.normal'.  To debug, you just manually
      // change the symlink to point to env.dbg
      sh("ln -snf env.normal env");

      writeFile(file: "README.debugging",
                text: """\
If you want to be able to debug a running python process using gdb
(to debug hangs or segfaults, say), do the following:
    ln -snf env.dbg env
    <run your python process>
    gdb -p <python process id, from 'ps' or similar>
    (gdb) py-bt    # etc
For more information, see https://wiki.python.org/moin/DebuggingWithGdb
""");
   }

   // There's no point in calling activate directly since we are not
   // a shell.  Instead, we just set up the environment the same way
   // activate does.
   echo("Activating virtualenv ${pwd()}/env");
   withEnv(["VIRTUAL_ENV=${pwd()}/env",
            "PATH=${pwd()}/env/bin:${env.PATH}"]) {
      body();
   }
}
