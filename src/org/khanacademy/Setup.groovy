// Convenience class for specifying setup options for jenkins pipeline jobs.
//
// You can just use the `properties` step directly for this, but it
// has a rather obscure syntax, and we can make convenience functions
// for some common combinations that we use.
//
// Note that this *OVERRIDES* the settings that occur on a jenkins
// pipeline config page.  (In fact, when this is run, it will update
// the appropriate job xml file to match it.)  That's good because we
// want settings to be in code, not crazy xml.
//
// Some setup options we enable a value by default, and you must call
// an explicit function to override.
//
// Use like this from the top of a a groovy script.
//    new Setup().<option1>.<option2>....apply(steps);
// `steps` is a global var 


package org.khanacademy;

class Setup implements Serializable {
   // How many log files to keep around for this job.
   def numBuildsToKeep;
   // Values can be 'this' to say this job cannot be run concurrently,
   // or a list of labels to use with the 'throttle concurrent builds'
   // plugin.  We block (no concurrent builds) by default.
   def concurrentBuildCategories;
   // The values you set when running this job.
   def params;

   Setup() { 
      this.numBuildsToKeep = 100;
      this.concurrentBuildCategories = ['this'];
      this.params = [];
   }

   def setNumBuildsToKeep(num) {
      this.numBuildsToKeep = num;
   }
   def keepAllBuilds(num) {
      this.numBuildsToKeep = null;
   }

   def allowConcurrentBuilds() {
      this.concurrentBuildCategories = null;
   }
   // By default, block builds so only one instance of this job
   // runs at a time.  If you pass in labels, the only one job
   // with a given label can run at a time.
   def blockBuilds(labels=['this']) {
      this.concurrentBuildCategories = labels;
   }

   def addBooleanParam(name, description, defaultValue=false) {
      this.params << new booleanParam(name: name, description: description,
                                      defaultValue: defaultValue);
   }
   def addStringParam(name, description, defaultValue='') {
      this.params << new string(name: name, description: description,
                                defaultValue: defaultValue);
   }
   def addChoicesParam(name, description, choices) {
      this.params << new choice(name: name, description: description,
                                choices: choices);
   }

   def apply(steps) {
      def props = [];
      if (this.numBuildsToKeep) {
         props << new logRotator(
            numToKeepStr: this.numBuildsToKeep.toString());
      }
      if (this.concurrentBuildCategories && 
          this.concurrentBuildCategories.contains('this')) {
         props << new disableConcurrentBuilds();
         props.removeElement('this');
      }
      if (this.concurrentBuildCategories) {
         props << [$class: 'ThrottleJobProperty', 
                   categories: this.concurrentBuildCategories,
                   throttleEnabled: true, 
                   throttleOption: 'category'
                  ];
      }
      if (this.params) {
         props << new parameters(params);
      }

      steps.properties(props);
};
