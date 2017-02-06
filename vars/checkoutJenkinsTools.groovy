def call() {
     // TODO(csilvers): only do this once per workspace per script.
     dir("jenkins-tools") {
        git(url: "https://github.com/Khan/jenkins-tools",
            changelog: false, poll: false);
        // The built-in 'git' step is not great: it doesn't do
        // submodules, and it messes up the tracking with the remote.
        sh("git submodule update --init --recursive");
        sh("git branch --set-upstream-to origin/master master");
     }
}

