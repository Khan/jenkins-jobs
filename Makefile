check:
	for f in *_test.py; do echo "--- $$f"; ./$$f || exit $?; done
