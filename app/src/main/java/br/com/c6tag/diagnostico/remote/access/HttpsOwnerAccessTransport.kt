package br.com.c6tag.diagnostico.remote.access

import br.com.c6tag.diagnostico.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.IDN
import java.net.URI
import java.net.URL
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface HttpsConnectionFactory {
    fun open(url: URL): HttpsURLConnection
}

internal class HttpsOwnerAccessTransport(
    ownerApiOrigin: String = BuildConfig.OWNER_API_ORIGIN,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { url ->
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("HTTPS connection unavailable")
        }
        connection
    },
) : OwnerAccessTransport {
    override val origin: String? = canonicalOwnerApiOrigin(ownerApiOrigin)
    override val configurationFailure: OwnerAccessFailureReason? =
        if (ownerApiOrigin.isEmpty()) {
            OwnerAccessFailureReason.NOT_CONFIGURED
        } else if (origin == null) {
            OwnerAccessFailureReason.INVALID_ORIGIN
        } else {
            null
        }

    override suspend fun execute(
        method: OwnerAccessMethod,
        path: OwnerAccessPath,
        credential: OwnerAccessCredential,
        body: String?,
    ): OwnerAccessTransportResult {
        val configuredOrigin = origin ?: return OwnerAccessTransportResult.RequestRejected
        if (
            credential.origin != configuredOrigin ||
            !credential.isHeaderSafe ||
            !isExpectedRequest(method, path, credential, body, configuredOrigin)
        ) {
            return OwnerAccessTransportResult.RequestRejected
        }

        val requestBody = body?.toByteArray(StandardCharsets.UTF_8)
        if (requestBody != null && requestBody.size > MAX_OWNER_ACCESS_RESPONSE_BYTES) {
            return OwnerAccessTransportResult.RequestRejected
        }

        val url = try {
            URL(configuredOrigin + path.value)
        } catch (_: IOException) {
            return OwnerAccessTransportResult.RequestRejected
        }

        return executeOnIoDispatcher(
            url = url,
            method = method,
            credential = credential,
            requestBody = requestBody,
        )
    }

    private suspend fun executeOnIoDispatcher(
        url: URL,
        method: OwnerAccessMethod,
        credential: OwnerAccessCredential,
        requestBody: ByteArray?,
    ): OwnerAccessTransportResult = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpsURLConnection?>()
        continuation.invokeOnCancellation {
            activeConnection.get()?.disconnect()
        }

        val request = Runnable {
            if (!continuation.isActive) {
                return@Runnable
            }

            var connection: HttpsURLConnection? = null
            try {
                connection = connectionFactory.open(url)
                activeConnection.set(connection)
                if (!continuation.isActive) {
                    connection.disconnect()
                    return@Runnable
                }

                configureConnection(
                    connection = connection,
                    method = method,
                    credential = credential,
                    hasBody = requestBody != null,
                )
                if (requestBody != null) {
                    connection.outputStream.use { output ->
                        output.write(requestBody)
                    }
                }
                connection.connect()
                if (!continuation.isActive) {
                    return@Runnable
                }

                val statusCode = connection.responseCode
                if (!continuation.isActive) {
                    return@Runnable
                }
                val responseStream = if (statusCode >= 400) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                val response = responseStream?.use(::readBoundedResponse)
                    ?: BoundedResponse(ByteArray(0), tooLarge = false)
                val result = if (response.tooLarge) {
                    OwnerAccessTransportResult.ResponseTooLarge(statusCode)
                } else {
                    OwnerAccessTransportResult.Response(statusCode, response.bytes)
                }
                continuation.resumeIfActive(result)
            } catch (_: IOException) {
                continuation.resumeIfActive(OwnerAccessTransportResult.NetworkFailure)
            } catch (failure: RuntimeException) {
                continuation.resumeWithExceptionIfActive(failure)
            } finally {
                connection?.disconnect()
                if (connection != null) {
                    activeConnection.compareAndSet(connection, null)
                }
            }
        }

        try {
            ioDispatcher.dispatch(continuation.context, request)
        } catch (_: RejectedExecutionException) {
            continuation.resumeIfActive(OwnerAccessTransportResult.DispatchRejected)
        }
    }

    private fun configureConnection(
        connection: HttpsURLConnection,
        method: OwnerAccessMethod,
        credential: OwnerAccessCredential,
        hasBody: Boolean,
    ) {
        connection.requestMethod = method.name
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", JSON_CONTENT_TYPE)
        connection.setRequestProperty("Authorization", "Bearer ${credential.secret.reveal()}")
        connection.doInput = true
        connection.doOutput = hasBody
        if (hasBody) {
            connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
        }
    }

    private fun isExpectedRequest(
        method: OwnerAccessMethod,
        path: OwnerAccessPath,
        credential: OwnerAccessCredential,
        body: String?,
        configuredOrigin: String,
    ): Boolean =
        when (path) {
            OwnerAccessPath.OWNER_MONITOR ->
                method == OwnerAccessMethod.GET &&
                    credential.kind == OwnerAccessCredentialKind.FIREBASE_ID_TOKEN &&
                    credential.hostContext == null &&
                    body == null

            OwnerAccessPath.HOST_ACTIVATIONS ->
                method == OwnerAccessMethod.POST &&
                    credential.kind == OwnerAccessCredentialKind.FIREBASE_ID_TOKEN &&
                    credential.hostContext == null &&
                    body?.matches(ACTIVATION_BODY_PATTERN) == true

            OwnerAccessPath.INSTALLATION -> {
                val context = credential.hostContext
                method == OwnerAccessMethod.GET &&
                    credential.kind == OwnerAccessCredentialKind.INSTALLATION_ACCESS_TOKEN &&
                    body == null &&
                    context != null &&
                    context.origin == configuredOrigin &&
                    context.owner == credential.owner
            }
        }

    private fun readBoundedResponse(input: java.io.InputStream): BoundedResponse {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)

        while (true) {
            val bytesRemainingToCheck = MAX_OWNER_ACCESS_RESPONSE_BYTES + 1 - output.size()
            if (bytesRemainingToCheck <= 0) {
                return BoundedResponse(ByteArray(0), tooLarge = true)
            }

            val bytesRead = input.read(
                buffer,
                0,
                minOf(buffer.size, bytesRemainingToCheck),
            )
            if (bytesRead < 0) {
                return BoundedResponse(output.toByteArray(), tooLarge = false)
            }
            if (bytesRead == 0) {
                val singleByte = input.read()
                if (singleByte < 0) {
                    return BoundedResponse(output.toByteArray(), tooLarge = false)
                }
                output.write(singleByte)
            } else {
                output.write(buffer, 0, bytesRead)
            }
            if (output.size() > MAX_OWNER_ACCESS_RESPONSE_BYTES) {
                return BoundedResponse(ByteArray(0), tooLarge = true)
            }
        }
    }

    private fun CancellableContinuation<OwnerAccessTransportResult>.resumeIfActive(
        result: OwnerAccessTransportResult,
    ) {
        if (isActive) {
            resume(result)
        }
    }

    private fun CancellableContinuation<OwnerAccessTransportResult>.resumeWithExceptionIfActive(
        failure: RuntimeException,
    ) {
        if (isActive) {
            resumeWithException(failure)
        }
    }

    private class BoundedResponse(
        val bytes: ByteArray,
        val tooLarge: Boolean,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val READ_BUFFER_SIZE = 4_096
        const val JSON_CONTENT_TYPE = "application/json"
        val ACTIVATION_BODY_PATTERN =
            Regex("""\{"confirmation":true,"expectedGeneration":"(0|[1-9][0-9]*)"\}""")
    }
}

internal fun canonicalOwnerApiOrigin(configuredOrigin: String): String? {
    if (
        configuredOrigin.isEmpty() ||
        configuredOrigin != configuredOrigin.trim() ||
        configuredOrigin.any { character -> character.isWhitespace() || character.isISOControl() } ||
        configuredOrigin.contains('\\')
    ) {
        return null
    }

    val uri = try {
        URI(configuredOrigin)
    } catch (_: URISyntaxException) {
        return null
    }
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        uri.rawPath !in setOf("", "/")
    ) {
        return null
    }

    val authority = uri.rawAuthority ?: return null
    if (
        authority.isEmpty() ||
        authority.any { character -> character.isWhitespace() || character.isISOControl() } ||
        authority.contains('@') ||
        authority.contains('%') ||
        authority.contains('\\') ||
        authority.endsWith(':')
    ) {
        return null
    }

    val parsedHost = uri.host?.takeIf(String::isNotBlank) ?: return null
    val port = uri.port
    if (port != -1 && port !in 1..65_535) {
        return null
    }

    val canonicalHost = if (parsedHost.startsWith('[') && parsedHost.endsWith(']')) {
        parsedHost.lowercase(Locale.ROOT)
    } else {
        try {
            IDN.toASCII(parsedHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    if (canonicalHost.isEmpty()) {
        return null
    }

    val canonicalPort = if (port == -1 || port == 443) "" else ":$port"
    return "https://$canonicalHost$canonicalPort"
}
