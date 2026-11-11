// Groovy script that points people to the new way to deploy a znd.
//
// ZNDs are now deployed by webapp's deploy-znd GitHub Actions workflow.
// This job is kept only so that people who come here out of habit see,
// on its "Build with Parameters" page, where to go instead.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;


new Setup(steps

).addStringParam(
   "README",
   """<b>This job is retired.  Deploy ZNDs with webapp's deploy-znd GitHub
Actions workflow instead: either update webapp and run
<code>deploy/deploy_znd.py</code> (it now dispatches the workflow with
<code>gh</code>), or start it from
<a href="https://github.com/Khan/webapp/actions/workflows/deploy-znd.yml">https://github.com/Khan/webapp/actions/workflows/deploy-znd.yml</a>.</b>""",
   ""

).apply();
