// We use these user-defined steps from vars/:
//import vars.onMaster

def call(key, Closure body) {
   if (key == null) {
      body();
      return;
   }

   onMaster('1m') {
      def cacheValue = sh(script: "redis-cli --raw GET '${key}'",
                          returnStdout: true).trim();
      // A cache hit!  We don't need to do any work.
      if (cacheValue == "1") {
         echo "Cache hit on '${key}' -- not running the body of this job.";
         return;
      }
   }

   body();

   // This only gets run if body() does not throw (because the build
   // was a success).  But we check explicitly for currentBuild.status
   // anyway in case the user explicitly set the status to not-success.
   if (currentBuild.status == null || currentBuild.status == "SUCCESS") {
      onMaster('1m') {
         sh("redis-cli SET '${key}' '1'");
      }
   }
}
