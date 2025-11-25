static String _buildServiceFilter(String version_id, String service) {
  return "(resource.labels.service_name=\"{service}\" AND " +
         "resource.labels.revision_name=\"${service}-rev-${version_id}\")"
}

// Returns the Google Cloud Log Viewer URL for the given Version ID and list of
// services.
static String logViewerUrl(String version_id, java.util.ArrayList services) {
    // This query is a bit tricky. It contains newlines and double quotes
    // intentionally and uses a multiline string (the 3 quotation marks
    // delimiters)
    def query = """resource.type="cloud_run_revision"
                  |(
                  |  ${services
                        .collect { _buildServiceFilter(version_id, it) }
                        .join(" OR ") }
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
