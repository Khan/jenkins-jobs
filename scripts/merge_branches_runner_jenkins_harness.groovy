#!/usr/bin/env groovy
import groovy.json.JsonOutput

Map<String, String> parseArgs(String[] args) {
    Map<String, String> out = [:]
    for (int i = 0; i < args.length; i++) {
        if (args[i].startsWith("--") && i + 1 < args.length) {
            out[args[i].substring(2)] = args[i + 1]
            i++
        }
    }
    return out
}

Map runProcess(List<String> cmd, File cwd, boolean captureOutput = true) {
    ProcessBuilder pb = new ProcessBuilder(cmd)
    pb.directory(cwd)
    pb.redirectErrorStream(true)
    Process p = pb.start()
    String out = captureOutput ? p.inputStream.getText("UTF-8").trim() : ""
    int rc = p.waitFor()
    return [command: cmd.join(" "), exitCode: rc, output: out]
}

void appendApiLog(String method, String commitId, String status, String sha1, String gaeVersionName) {
    String path = System.getenv("MERGE_BRANCHES_API_LOG")
    if (!path) {
        return
    }
    def payload = [
        method: method,
        commit_id: commitId,
        status: status,
        sha1: sha1,
        gae_version_name: gaeVersionName,
    ]
    new File(path) << JsonOutput.toJson(payload) + "\n"
}

Map<String, String> opts = parseArgs(args)
String revisionsInput = opts["git-revisions"]
String commitId = opts["commit-id"]
String description = opts["revision-description"] ?: ""
if (!revisionsInput || !commitId) {
    System.err.println("Usage: --git-revisions <...> --commit-id <...> [--revision-description <...>]")
    System.exit(2)
}

File workspace = new File(System.getenv("MERGE_BRANCHES_WORKDIR") ?: ".").canonicalFile
workspace.mkdirs()
File[] currentDir = [workspace]

Closure dirStep = { String path, Closure body ->
    File prev = currentDir[0]
    currentDir[0] = new File(prev, path).canonicalFile
    try {
        body.call()
    } finally {
        currentDir[0] = prev
    }
}

Closure echoStep = { String msg -> println(msg) }
Closure errorStep = { String msg -> throw new RuntimeException(msg) }

Class failedBuildClass = null
Class execResultClass = null

Object notifyStep = new Object() {
    void fail(String msg) {
        throw failedBuildClass.getConstructor(String.class).newInstance(msg)
    }

    void fail(String msg, Throwable t) {
        throw failedBuildClass.getConstructor(String.class)
            .newInstance(msg + ": " + t.getMessage())
    }

    void rethrowIfAborted(Throwable t) {
        // No-op in harness.
    }
}

Object withSecretsStep = new Object() {
    void slackAlertlibOnly(Closure c) { c.call() }
}

Object execStep = new Object() {
    private Object asExecResult(Map r) {
        def result = execResultClass.newInstance()
        result.command = r.command
        result.exitCode = r.exitCode
        result.output = r.output
        return result
    }

    private Object runGit(List<String> args) {
        return runProcess(args, currentDir[0])
    }

    private Object runSafeGit(List<String> args) {
        String subcommand = args[1]
        if (subcommand == "clone") {
            return runProcess(["git", "clone", "-q", args[2], args[3]], currentDir[0])
        }
        if (subcommand == "simple_fetch") {
            return runProcess(["git", "-C", args[2], "fetch", "--all", "--tags"], currentDir[0])
        }
        if (subcommand == "clean_branches") {
            // Safe no-op for test harness; we always use fresh clones.
            return [command: args.join(" "), exitCode: 0, output: ""]
        }
        throw new RuntimeException("Unsupported safe_git subcommand in harness: ${subcommand}")
    }

    private Object runAny(List<String> arglist) {
        if (!arglist.isEmpty() && arglist[0] == "jenkins-jobs/safe_git.sh") {
            return runSafeGit(arglist)
        }
        return runGit(arglist)
    }

    void call(List<String> arglist) {
        def r = runAny(arglist)
        if (r.exitCode != 0) {
            throw new RuntimeException(r.output)
        }
    }

    String outputOf(List<String> arglist) {
        def r = runAny(arglist)
        if (r.exitCode != 0) {
            throw new RuntimeException(r.output)
        }
        return r.output
    }

    Integer statusOf(List<String> arglist) {
        def r = runAny(arglist)
        return r.exitCode
    }

    Object runCommand(List<String> arglist) {
        return asExecResult(runAny(arglist))
    }
}

Binding binding = new Binding()
binding.setProperty("env", [
    KA_WEBAPP_REPO_URL: (System.getenv("KA_WEBAPP_REPO_URL") ?: "git@github.com:Khan/webapp"),
    BUILD_TAG: (System.getenv("BUILD_TAG") ?: "merge-branches-harness"),
])
binding.setProperty("dir", dirStep)
binding.setProperty("echo", echoStep)
binding.setProperty("error", errorStep)
binding.setProperty("notify", notifyStep)
binding.setProperty("withSecrets", withSecretsStep)
binding.setProperty("exec", execStep)
binding.setProperty("fileExists", { String p -> new File(currentDir[0], p).exists() })
binding.setProperty("readFile", { String p -> new File(currentDir[0], p).text })
binding.setProperty("writeFile", { Map m -> new File(currentDir[0], m.file as String).text = m.text as String })

String repoRoot = System.getenv("MERGE_BRANCHES_REPO_ROOT") ?: "."
String kaGitSource = new File(repoRoot, "vars/kaGit.groovy").text
String harnessPrelude = """
class FailedBuild extends RuntimeException {
    FailedBuild(String message) { super(message) }
}

class ExecResult {
    String command
    Integer exitCode
    String output
}
"""
GroovyShell shell = new GroovyShell(binding)
Script kaGit = shell.parse(harnessPrelude + "\n" + kaGitSource)
failedBuildClass = kaGit.class.classLoader.loadClass("FailedBuild")
execResultClass = kaGit.class.classLoader.loadClass("ExecResult")

try {
    String tagName = "buildmaster-${commitId}-${new Date().format('yyyyMMdd-HHmmss')}"
    String sha1 = kaGit.invokeMethod("mergeRevisions", [revisionsInput, tagName, description] as Object[])
    String gaeVersionName = System.getenv("GAE_VERSION_NAME") ?: ""
    appendApiLog("notifyMergeResult", commitId, "success", sha1, gaeVersionName)
    println(sha1)
} catch (Throwable t) {
    appendApiLog("notifyMergeResult", commitId, "failed", null, null)
    System.err.println(t.getMessage())
    System.exit(1)
}
