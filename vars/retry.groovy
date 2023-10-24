// We use these user-defined steps from vars/:
//import vars.notify

def call(options=null, Closure body) {
   options = options ?: [:];
   def retryCount = options.retryCount ?: 3;
   def sleepTime = options.sleepTime ?: 10;
   for (def i = 0; i <= retryCount; i++) {
      try {
         return body();
      } catch (e) {
         notify.rethrowIfAborted(e);
         def msgStart = "Try #${i}: got ${e}."
         if (i == retryCount) {
            echo("${msgStart} Giving up.");
            throw e;
         } else if (options.rejectFn && options.rejectFn(e)) {
            echo("${msgStart} Non-retriable error.");
            throw e;
         } else {
            sleep(sleepTime);   // wait 10 sec to retry again by default
            echo("${msgStart} Retrying.");
         }
      }
   }
}
