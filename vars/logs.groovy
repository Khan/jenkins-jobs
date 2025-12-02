String _buildServiceFilter(String version_id, String service) {
  return "(resource.labels.service_name=\"${service}\" AND resource.labels.revision_name=\"${service}-rev-${version_id}\")"
}

// Returns the Google Cloud Log Viewer URL for the given Version ID
//
// CAUTION(nathanjd): ideally we'd type `services` here as some sort of list,
// but we construct the value that we pass in here in different ways which
// yields different types. If we pass a value of the wrong type, Java/Groovy
// will throw an error saying it couldn't find this method, which is
// catastrophic especially during in deploy-webapp.
String logViewerUrl(String version_id, def services) {
    // This query is a bit tricky. It contains newlines and double quotes
    // intentionally and uses a multiline string (the 3 quotation marks
    // delimiters)
    def query = """resource.type="cloud_run_revision"
                  |(
                  |  ${services
                        .collect { _buildServiceFilter(version_id, it) }
                        .join(" OR\n  ") }
                  |)""".stripMargin();

    // URLEncoder encodes spaces as "+", but Google doesn't like that so we
    // manually replace them with a URL-encoded space (ie. "%20").
    query = java.net.URLEncoder.encode(query);
    query = query.replaceAll(/\+/, "%20");

    // Yes! We specify "query;query=..." here. That's intentional and how GCP
    // wants it (they're called matrix parameters).
    return "https://console.cloud.google.com/logs/query;" +
           "query=${query}?project=khan-academy";
}
