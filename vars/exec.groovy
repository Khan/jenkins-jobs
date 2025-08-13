import java.util.regex.Matcher

def shellEscape(s) {
   // We don't need to do escaping if the string does not contain any
   // characters that are meaningful to the shell.
   if (s ==~ /[a-zA-Z0-9\/.:=_-]+/) {
      return s;
   }
   return "'" + s.replace("'", "'\\''") + "'";
}

def shellEscapeList(lst) {
   def retval = "";
   // We have to use C-style iterators in jenkins pipeline scripts.
   for (def i = 0; i < lst.size(); i++) {
      retval += shellEscape(lst[i]) + " ";
   }
   return retval;
}

// TODO(csilvers): figure out how to support returnStdout and returnStatus
// the same way `sh` does.  When I try to implement it, I get errors about
// not being able to serialize a hash-map.
def call(arglist) {
   sh(shellEscapeList(arglist));
}


// A useful utility, similar to backticks in perl.
def outputOf(arglist) {
   return sh(script: shellEscapeList(arglist), returnStdout: true).trim();
}

// A useful utility, similar to system() in perl.
def statusOf(arglist) {
   return sh(script: shellEscapeList(arglist), returnStatus: true);
}

// Result of executing a shell command.
class ExecResult {
   // Escaped command that was run.
   String command

   // Resulting exit code.
   Integer exitCode

   // Combined output of stdout and stderr.
   String output
}

// Execute a list of args as a shell command, then return the exit code, the
// combined stdout + stderr, and the escaped command that was run.
ExecResult runCommand(List<String> arglist) {
   String cmd = shellEscapeList(arglist)
   // Redirecting cmd stderr to stdout, in order to return it if cmd failed
   String output = "";
   Integer rc = 0;
   withEnv(["RC=0"]) {
      output = sh(script: """
         set +x
         ( ${cmd} ) 2>&1 || RC=\$?
         echo "EXIT_CODE:\$RC"
      """, returnStdout: true).trim();
   }
   // Extracting exit code from the last line of output
   Matcher exitCodeMatch = output =~ /(?s)(.*)EXIT_CODE:(\d+)$/;
   if (exitCodeMatch) {
      rc = exitCodeMatch[0][2].toInteger();
      output = exitCodeMatch[0][1].trim();
   }
   return new ExecResult(exitCode: rc, output: output, command: cmd);
}
