// Returns a triple: timeout amount as an integer (e.g. 20),
// timeout unit as a string (e.g. "MINUTES"), timeout strategy as
// a string (e.g. "ABSOLUTE").

def call(def timeoutString, Closure body) {
   // I wanted to make this top part a separate function,
   // parseTimeout, but it appears pipeline-groovy can't return a list
   // or dict from a function call, so I just inline it. :-/

   // The timeout units are named as per the built-in `timeout` step.  See
   // https://jenkins.khanacademy.org/job/deploy/job/e2e-test/pipeline-syntax/
   def _UNITS_MAP = [
      "s": "SECONDS",
      "m": "MINUTES",
      "h": "HOURS",
      "d": "DAYS",
   ];

   def strategy = "ABSOLUTE";
   def units = "MINUTES";
   def value;

   def strategyAndValue = timeoutString.split(":");
   if (strategyAndValue.size() < 1 || strategyAndValue.size() > 2) {
      error("Invalid timeout-value '${timeoutString}': " +
            "expecting '<strategy>:<amount><unit>'");
   }
   if (strategyAndValue.size() == 2) {
      strategy = strategyAndValue[0];
   }

   def valueAndUnits = strategyAndValue[-1];
   if (!valueAndUnits.size()) {
      error("Invalid timeout-value '${timeoutString}': no value specified");
   }

   // Assume we end with a units-suffix.  If not, units will return `null`.
   units = _UNITS_MAP[valueAndUnits[-1]];
   if (units) {
      if (valueAndUnits.size() <= 1) {
         error("Invalid timeout-value '${timeoutString}': no value specified");
      }
      value = valueAndUnits[0..-2];
   } else {
      value = valueAndUnits;
      units = "MINUTES";        // the default unit
   }

   try {
      value = value.toInteger();
   } catch (e) {
      error("Invalid timeout-value '${timeoutString}': " +
            "value not a number, or invalid suffix (not s/m/h/d)");
   }

   // The actual main work of withTimeout().

   if (strategy == 'ABSOLUTE') {
      timeout(time: value, unit: units) {
         body();
      }
   } else {
      error("Invalid timeout-value '${timeoutString}': " +
            "Unsupported strategy '${strategy}' -- sorry!");
   }
}

