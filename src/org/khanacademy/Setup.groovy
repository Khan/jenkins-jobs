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
// `steps` is a global var defined by the pipeline setup.


package org.khanacademy;

class Setup implements Serializable {
   // The global jenkins object that knows how to run steps.
   def steps;

   // How many log files to keep around for this job.
   // TODO(benkraft): Allow keeping more failures and fewer successes.
   def numBuildsToKeep;
   // If true, we only allow one instance of this job to run at a time.
   def disableConcurrentBuilds;
   // The cron schedule, if this job should be run on a schedule.
   def cronSchedule;
   // The values users set when running this job.
   def params;

   Setup(steps) {
      this.steps = steps;
      this.numBuildsToKeep = 100;
      this.disableConcurrentBuilds = true;
      this.cronSchedule = null;
      this.params = [];
   }

   def resetNumBuildsToKeep(num) {
      this.numBuildsToKeep = num;
      return this;
   }
   def keepAllBuilds(num) {
      this.numBuildsToKeep = null;
      return this;
   }

   def allowConcurrentBuilds() {
      this.disableConcurrentBuilds = false;
      return this;
   }

   // Schedule should be, e.g. 'H 2 * * 1-5'
   def addCronSchedule(schedule) {
      this.cronSchedule = schedule;
      return this;
   }

   def addParam(param) {
      this.params << param;
      return this;
   }
   def addBooleanParam(name, description, defaultValue=false) {
      return this.addParam(
         this.steps.booleanParam(
            name: name, description: description, defaultValue: defaultValue));
   }
   def addStringParam(name, description, defaultValue='') {
      return this.addParam(
         this.steps.string(
            name: name, description: description, defaultValue: defaultValue));
   }
   def addChoiceParam(name, description, choices) {
      return this.addParam(
         this.steps.choice(
            name: name, description: description, choices: choices.join("\n")));
   }

   def apply() {
      def props = [];
      if (this.numBuildsToKeep) {
         props << this.steps.buildDiscarder(
            this.steps.logRotator(artifactDaysToKeepStr: '',
                                  artifactNumToKeepStr: '',
                                  daysToKeepStr: '',
                                  numToKeepStr: this.numBuildsToKeep.toString()));
      }
      if (this.disableConcurrentBuilds) {
         props << this.steps.disableConcurrentBuilds();
      }
      if (this.cronSchedule) {
         props << this.steps.pipelineTriggers(
            [
               [$class: "hudson.triggers.TimerTrigger",
                spec: this.cronSchedule]
            ]);
      }
      if (this.params) {
         props << this.steps.parameters(params);
      }

      this.steps.properties(props);
   }
};
