// Returns the Google Cloud Log Viewer URL for the given Version ID
String logViewerUrl(String version_id) {
    // This query is a bit tricky. It contains newlines and double quotes
    // intentionally and uses a multiline string (the 3 quotation marks
    // delimiters)
    def query = """(resource.type="gae_app" AND resource.labels.version_id="${version_id})"
                  |OR
                  |(resource.type="cloud_run_revision" AND resource.labels.revision_name="progress-rev-${version_id}"))""".stripMargin();

    // URLEncoder encodes spaces as "+", but Google doesn't like that so we
    // manually replace them with a URL-encoded space (ie. "%20").
    query = java.net.URLEncoder.encode(query);
    query = query.replaceAll(/\+/, "%20");

    // Yes! We specify "query;query=..." here. That's intentional and how GCP
    // wants it.
    return "https://console.cloud.google.com/logs/query;" +
           "query=${query}?project=khan-academy";
}
