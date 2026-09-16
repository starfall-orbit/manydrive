package com.starfall.gsadrive.data

import com.starfall.gsadrive.tr

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import kotlin.time.Duration.Companion.hours
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import aws.smithy.kotlin.runtime.content.writeToFile
import java.io.File

data class S3Config(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val region: String = "us-east-1"
)

/** S3/MinIO-compatible browser using AWS Signature V4 via the official Kotlin SDK. */
object S3Api {
    private fun client(config: S3Config) = S3Client {
        region = config.region.ifBlank { "us-east-1" }
        endpointUrl = Url.parse(config.endpoint)
        forcePathStyle = true
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = config.accessKey
            secretAccessKey = config.secretKey
        }
    }

    suspend fun delete(config: S3Config, file: DriveFile) {
        client(config).use { client ->
            if (!file.isFolder) {
                require(file.id.isNotEmpty()) { "An object key is required" }
                client.deleteObject(DeleteObjectRequest {
                    bucket = config.bucket
                    key = file.id
                })
                return
            }
            deleteS3Folder(file.id,
                listPage = { prefix, token ->
                    val response = client.listObjectsV2(ListObjectsV2Request {
                        bucket = config.bucket
                        this.prefix = prefix
                        continuationToken = token
                        // No delimiter: include every descendant, not just immediate children.
                        maxKeys = 1_000
                    })
                    S3DeletePage(
                        keys = response.contents.orEmpty().map { requireNotNull(it.key) },
                        nextToken = if (response.isTruncated == true)
                            requireNotNull(response.nextContinuationToken) { "Missing S3 continuation token" }
                        else null
                    )
                },
                deleteKeys = { keys ->
                    val response = client.deleteObjects(DeleteObjectsRequest {
                        bucket = config.bucket
                        delete = Delete {
                            objects = keys.map { value -> ObjectIdentifier { key = value } }
                            quiet = true
                        }
                    })
                    // S3 can return HTTP 200 while individual deletions fail.
                    check(response.errors.isNullOrEmpty()) {
                        response.errors.orEmpty().take(3).joinToString("; ") {
                            "${it.key}: ${it.code} ${it.message.orEmpty()}"
                        }
                    }
                }
            )
        }
    }

    suspend fun upload(config: S3Config, key: String, mimeType: String, file: File) {
        client(config).use { client ->
            client.putObject(PutObjectRequest {
                bucket = config.bucket
                this.key = key
                contentType = mimeType
                body = ByteStream.fromFile(file)
            })
        }
    }

    suspend fun createFolder(config: S3Config, key: String) {
        client(config).use { client ->
            client.putObject(PutObjectRequest {
                bucket = config.bucket
                this.key = key
                body = ByteStream.fromBytes(ByteArray(0))
            })
        }
    }

    internal suspend fun downloadSource(config: S3Config, key: String): DownloadSource = client(config).use { client ->
        val request = client.presignGetObject(GetObjectRequest {
            bucket = config.bucket
            this.key = key
        }, 24.hours)
        DownloadSource(request.url.toString())
    }

    suspend fun downloadTo(config: S3Config, key: String, target: File) {
        target.parentFile?.mkdirs()
        client(config).use { client ->
            client.getObject(GetObjectRequest {
                bucket = config.bucket
                this.key = key
            }) { response ->
                requireNotNull(response.body) { tr("S3 không trả về nội dung tệp.") }.writeToFile(target)
            }
        }
    }

    suspend fun list(config: S3Config, prefix: String = ""): List<DriveFile> = client(config).use { client ->
        val response = client.listObjectsV2(ListObjectsV2Request {
            bucket = config.bucket
            this.prefix = prefix
            delimiter = "/"
            maxKeys = 1_000
        })
        val folders = response.commonPrefixes.orEmpty().mapNotNull { item -> item.prefix }.map { key ->
            DriveFile(id = key, name = key.removePrefix(prefix).removeSuffix("/"), mimeType = "application/vnd.google-apps.folder", modifiedTime = null)
        }
        val objects = response.contents.orEmpty().filter { it.key != prefix }.mapNotNull { item ->
            item.key?.let { key ->
                DriveFile(id = key, name = key.removePrefix(prefix), mimeType = guessMimeType(key), modifiedTime = item.lastModified?.toString(), size = item.size)
            }
        }
        folders + objects
    }

    private fun guessMimeType(key: String): String = when (key.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "html", "css", "py", "sh" -> "text/plain"
        else -> "application/octet-stream"
    }
}
