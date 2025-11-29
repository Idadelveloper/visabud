package online.visabud.app.visabud_multiplatform.doc

private class IOSDocumentPipeline : DocumentPipeline {
    override suspend fun extractFields(imagePath: String): Map<String, String> {
        return emptyMap()
    }

    override suspend fun reviewDocument(parsedFields: Map<String, String>, targetVisa: String): String {
        return "{\"status\":\"ERROR\",\"issues\":[{\"field\":\"_\",\"problem\":\"Not yet implemented on iOS\",\"severity\":\"low\"}],\"suggestions\":[\"Use Android build for demo\"]}"
    }
}

actual fun documentPipeline(): DocumentPipeline = IOSDocumentPipeline()
