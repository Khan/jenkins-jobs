check:
	for f in *_test.py; do echo "--- $$f"; ./$$f || exit $?; done

# The security model of the groovy script runner now requires that all
# scripts be in a jar. The following task creates said jar.
groovy:
	jar cvf Cancel.jar CancelDownstream.groovy CancelSiblings.groovy
