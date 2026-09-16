package com.starfall.gsadrive.data

internal data class S3DeletePage(val keys: List<String>, val nextToken: String? = null)

/** Delete only this folder prefix, in bounded batches, including its marker object. */
internal suspend fun deleteS3Folder(
    key: String,
    listPage: suspend (String, String?) -> S3DeletePage,
    deleteKeys: suspend (List<String>) -> Unit
) {
    require(key.isNotEmpty() && key != "/") { "A folder key is required; bucket root cannot be deleted" }
    val prefix = if (key.endsWith('/')) key else "$key/"
    var token: String? = null
    do {
        val page = listPage(prefix, token)
        require(page.keys.all { it.startsWith(prefix) }) { "S3 returned an object outside the selected folder" }
        check(page.nextToken == null || (page.nextToken.isNotEmpty() && page.nextToken != token)) {
            "Invalid S3 continuation token"
        }
        page.keys.chunked(1_000).forEach { deleteKeys(it) }
        token = page.nextToken
    } while (token != null)
}
