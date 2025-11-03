// Returns the Google Cloud Log Viewer URL for the given Version ID
String logViewerUrl(String version_id) {
    // This query is a bit tricky. It contains newlines and double quotes
    // intentionally and uses a "here document" (not sure what its called in
    // Groovy, but its the 3 quotation marks that start/end a multi-line string
    // thing)
    def query = """resource.type="gae_app" OR resource.type="cloud_run_revision"
                  |resource.labels.version_id="${version_id}\"""".stripMargin();

    // Yes! We specify "query;query=..." here. That's intentional and how GCP
    // wants it.
    return "https://console.cloud.google.com/logs/query;" +
           "query=" + java.net.URLEncoder.encode(query, "UTF-8") +
           "?project=khan-academy";
}
