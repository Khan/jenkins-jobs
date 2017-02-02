// Convenience class for specifying setup options for jenkins pipeline jobs.
//
// You can just use the `properties` step directly for this, but it
// has a rather obscure syntax, and we can make convenience functions
// for some common combinations that we use.
//
// And in fact, we do some setup work, like modifying env.PATH to set
// up virtualenv, that is not technically related to the `properties`
// step.
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
   // The global jenkins object that knows how to run steps,
   // and the global environment made available to groovy scripts.
   def steps;
   def env;

   // How many log files to keep around for this job.
   def numBuildsToKeep;

   // Values can be 'this' to say this job cannot be run concurrently,
   // or a list of labels to use with the 'throttle concurrent builds'
   // plugin.  We block (no concurrent builds) by default.
   def concurrentBuildCategories;

   // The parameters you set when running this job.
   def params;

   // Whether to set the global PATH to use a virtualenv (for python).
   // If true (the default), then apply() will set up the virtualenv.
   // It will allocate a node on master to do so.
   def useVirtualenv;

   // Whether to install jenkins-tools in the current workspace.
   // Default is true because most every script needs it.  If
   // useVirtualenv is set, this value is ignored since useVirtualenv
   // needs jenkins-tools.
   def installJenkinsTools;


   Setup(steps, env) {
      this.steps = steps;
      this.env = env;

      this.numBuildsToKeep = 100;
      this.concurrentBuildCategories = ['this'];
      this.params = [];
      this.useVirtualenv = true;
      this.installJenkinsTools = true;
   }

   def setNumBuildsToKeep(num) {
      this.numBuildsToKeep = num;
      return this;
   }
   def keepAllBuilds(num) {
      this.numBuildsToKeep = null;
      return this;
   }

   def allowConcurrentBuilds() {
      this.concurrentBuildCategories = null;
      return this;
   }
   // By default, block builds so only one instance of this job
   // runs at a time.  If you pass in labels, the only one job
   // with a given label can run at a time.
   def blockBuilds(labels=['this']) {
      this.concurrentBuildCategories = labels;
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
   def addChoicesParam(name, description, choices) {
      return this.addParam(
         this.steps.choice(
            name: name, description: description, choices: choices));
   }

   def useVirtualenv(v) {
      this.useVirtualenv = v;
   }

   def installJenkinsTools(v) {
      this.installJenkinsTools = v;
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
      if (this.concurrentBuildCategories &&
          this.concurrentBuildCategories.contains('this')) {
         props << this.steps.disableConcurrentBuilds();
         this.concurrentBuildCategories.removeElement('this');
      }
      if (this.concurrentBuildCategories) {
         props << [$class: 'ThrottleJobProperty',
                   categories: this.concurrentBuildCategories,
                   throttleEnabled: true,
                   throttleOption: 'category'
                  ];
      }
      if (this.params) {
         props << this.steps.parameters(params);
      }

      this.steps.properties(props);

      if (this.installJenkinsTools || this.useVirtualenv) {
         this.steps.node("master") {
            this.steps.timestamps {
               this.steps.dir("jenkins-tools") {
                  this.steps.git(url: "https://github.com/Khan/jenkins-tools",
                                 changelog: false, poll: false);
                  // Make it so scripts can use jenkins-tools's alertlib too.
                  this.steps.sh("git submodule update --init --recursive");
               }
               if (this.useVirtualenv) {
                  // This makes sure env/ exists.
                  this.steps.sh("./jenkins-tools/build.lib ensure_virtualenv");
                  this.steps.dir("env") {
                     this.env.VIRTUAL_ENV = this.steps.sh(
                        script: "pwd", returnStdout: true).trim();
                     this.env.PATH = (
                        "${this.env.VIRTUAL_ENV}/bin:${this.env.PATH}");
                  }
               }
            }
         }
      }
   }
};
