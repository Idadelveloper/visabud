package online.visabud.app.visabud_multiplatform.doc

private class JsDocumentPipeline : DocumentPipeline {
    override suspend fun extractFields(imagePath: String): Map<String, String> = emptyMap()
    override suspend fun reviewDocument(parsedFields: Map<String, String>, targetVisa: String): String = "{\"status\":\"ERROR\",\"issues\":[{\"field\":\"_\",\"problem\":\"Document pipeline not available on Web\",\"severity\":\"low\"}],\"suggestions\":[\"Use Android build for demo\"]}"
}

actual fun documentPipeline(): DocumentPipeline = JsDocumentPipeline()
