def call(options=null, Closure body) {
   options = options ?: [:];
   def retryCount = options.retryCount ?: 3;
   for (def i = 0; i <= retryCount; i++) {
      try {
         return body();
      } catch (e) {
         def msgStart = "Try #${i}: got ${e}."
         if (i == retryCount) {
            echo("${msgStart} Giving up.");
            throw e;
         } else if (options.rejectFn && options.rejectFn(e)) {
            echo("${msgStart} Non-retriable error.");
            throw e;
         } else {
            sleep(1);   // wait 1 sec to retry again
            echo("${msgStart} Retrying.");
         }
      }
   }
}
