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
