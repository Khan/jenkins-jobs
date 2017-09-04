// We use these user-defined steps from vars/:
//import vars.exec

def _getKey(key) {
   return exec.outputOf(["redis-cli", "--raw", "GET", key]);
}

def _setKey(key) {
   exec(["redis-cli", "SET", key, "1"]);
}

def _singleton(key, storeOnFailure, Closure body) {
   if (key == null) {
      body();
   } else if (_getKey(key) == "1") {
      echo "Cache hit on '${key}' -- not running the body of this job.";
   } else if (storeOnFailure) {
      try {
         body();
      } finally {
         // set the key whether or not body completes successfully.
         _setKey(key);
      }
   } else {
      body();

      // This only gets run if body() does not throw (because the build
      // was a success).  But we check explicitly for currentBuild.status
      // anyway in case the user explicitly set the status to not-success.
      if (currentBuild.result == null || currentBuild.result == "SUCCESS") {
         _setKey(key);
      }
   }
}


def call(key, Closure body) {
   _singleton(key, false, body);
}


// This is like singleton(), but will store that the job has been run
// even if it fails.
def storeEvenOnFailure(key, Closure body) {
   _singleton(key, true, body);
}
