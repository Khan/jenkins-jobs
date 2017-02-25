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

def call(arglist, returnStdout=false, returnStatus=false) {
   sh(script: shellEscapeList(arglist),
      returnStdout: returnStdout, returnStatus: returnStatus);
}

