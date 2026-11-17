package br.com.c6tag.diagnostico.diagnostic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

internal enum class DiagnosticRecorderError {
    EVENT_CREATION_FAILED,
    EVENT_PACKAGE_MISMATCH,
    STORE_OPERATION_FAILED,
    RECORDER_CLOSED,
}

internal enum class DiagnosticRecorderOperation {
    APPEND,
    PAGE,
    CLEAR,
    EXPORT_SNAPSHOT,
    SERIALIZED,
}

internal data class DiagnosticRecorderState(
    /** Monotonic count of successful serialized store operations. */
    val version: Long = 0L,
    val lastSuccess: DiagnosticRecorderOperation? = null,
    val lastSuccessfulId: Long? = null,
    /** Sticky for this recorder's lifetime; later success never erases a failure. */
    val errorCount: Long = 0L,
    val lastError: DiagnosticRecorderError? = null,
)

/**
 * Serializes storage operations off the caller thread. The queue is unbounded:
 * it never silently drops diagnostics, but a sustained producer faster than
 * disk can grow memory use until the process is stopped or storage catches up.
 */
internal class DiagnosticRecorder(
    private val store: DiagnosticStore,
    private val allowedPackage: String = "",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope,
) : AutoCloseable {
    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(DiagnosticRecorderState())
    private val worker: Job = scope.launch(ioDispatcher) {
        try {
            for (command in queue) {
                if (!isActive) {
                    failCommand(command)
                    break
                }
                process(command)
            }
        } finally {
            queue.close()
            failQueuedCommands()
        }
    }.also { job ->
        job.invokeOnCompletion {
            queue.close()
            failQueuedCommands()
        }
    }

    val state: StateFlow<DiagnosticRecorderState> = mutableState.asStateFlow()

    /**
     * Checks the package before evaluating [toEvent]. The event is checked
     * again before enqueueing so a faulty supplier cannot persist another
     * package's payload.
     */
    fun recordNotification(
        packageName: String,
        toEvent: () -> DiagnosticEvent,
    ) {
        if (allowedPackage.isBlank() || packageName != allowedPackage) return

        val event = try {
            toEvent()
        } catch (_: Exception) {
            recordFailure(DiagnosticRecorderError.EVENT_CREATION_FAILED)
            return
        }

        if (event.packageName != allowedPackage) {
            recordFailure(DiagnosticRecorderError.EVENT_PACKAGE_MISMATCH)
            return
        }
        enqueue(Command.Append(event))
    }

    /** Records only listener lifecycle events; notification event types are rejected. */
    fun recordLifecycle(
        type: DiagnosticEventType,
        observedAtMs: Long,
    ) {
        require(
            type == DiagnosticEventType.LISTENER_CONNECTED ||
                type == DiagnosticEventType.LISTENER_DISCONNECTED,
        )
        enqueue(
            Command.Append(
                DiagnosticEvent(
                    type = type,
                    observedAtMs = observedAtMs,
                ),
            ),
        )
    }

    suspend fun page(
        beforeId: Long? = null,
        limit: Int,
    ): List<StoredDiagnosticEvent> {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        val result = CompletableDeferred<List<StoredDiagnosticEvent>>()
        enqueue(Command.Page(beforeId, limit, result), result)
        return result.await()
    }

    suspend fun clear() {
        val result = CompletableDeferred<Unit>()
        enqueue(Command.Clear(result), result)
        result.await()
    }

    /** Monitor commands use the same lane as capture; never invoke from presentation. */
    internal suspend fun <T> runSerialized(block: () -> T): T {
        val result = CompletableDeferred<T>()
        enqueue(Command.Serialized(block, result), result)
        return result.await()
    }

    /**
     * Executes [block] on the storage worker. Its sequence must be consumed
     * within the callback; the store may back it with an open SQLite cursor.
     */
    suspend fun <T> exportSnapshot(
        block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
    ): T {
        val result = CompletableDeferred<T>()
        enqueue(Command.ExportSnapshot(block, result), result)
        return result.await()
    }

    /** Stops accepting work and lets already queued commands finish. */
    override fun close() {
        queue.close()
    }

    /** A suspending close barrier for owners that must wait for queued writes. */
    suspend fun closeAndJoin() {
        close()
        worker.join()
    }

    private fun enqueue(command: Command) {
        if (queue.trySend(command).isFailure) {
            recordFailure(DiagnosticRecorderError.RECORDER_CLOSED)
        }
    }

    private fun <T> enqueue(
        command: Command,
        result: CompletableDeferred<T>,
    ) {
        if (queue.trySend(command).isFailure) {
            recordFailure(DiagnosticRecorderError.RECORDER_CLOSED)
            result.completeExceptionally(DiagnosticRecorderOperationException())
        }
    }

    private fun process(command: Command) {
        when (command) {
            is Command.Append -> {
                try {
                    val id = store.append(command.event)
                    mutableState.update {
                        it.copy(
                            version = it.version + 1,
                            lastSuccess = DiagnosticRecorderOperation.APPEND,
                            lastSuccessfulId = id,
                        )
                    }
                } catch (_: Exception) {
                    recordFailure(DiagnosticRecorderError.STORE_OPERATION_FAILED)
                }
            }

            is Command.Page -> complete(
                command.result,
                { store.page(beforeId = command.beforeId, limit = command.limit) },
                { markSuccess(DiagnosticRecorderOperation.PAGE) },
            )

            is Command.Clear -> complete(
                command.result,
                { store.clear() },
                {
                    mutableState.update {
                        it.copy(
                            version = it.version + 1,
                            lastSuccess = DiagnosticRecorderOperation.CLEAR,
                            lastSuccessfulId = null,
                        )
                    }
                    Unit
                },
            )

            is Command.ExportSnapshot<*> -> processSnapshot(command)
            is Command.Serialized<*> -> processSerialized(command)
        }
    }

    private fun <T> complete(
        result: CompletableDeferred<T>,
        operation: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        try {
            val value = operation()
            onSuccess(value)
            result.complete(value)
        } catch (_: Exception) {
            recordFailure(DiagnosticRecorderError.STORE_OPERATION_FAILED)
            result.completeExceptionally(DiagnosticRecorderOperationException())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processSnapshot(command: Command.ExportSnapshot<*>) {
        val typedCommand = command as Command.ExportSnapshot<Any?>
        complete(
            typedCommand.result,
            { store.exportSnapshot(typedCommand.block) },
        ) {
            markSuccess(DiagnosticRecorderOperation.EXPORT_SNAPSHOT)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processSerialized(command: Command.Serialized<*>) {
        val typedCommand = command as Command.Serialized<Any?>
        complete(typedCommand.result, typedCommand.block) {
            markSuccess(DiagnosticRecorderOperation.SERIALIZED)
        }
    }

    private fun markSuccess(operation: DiagnosticRecorderOperation) {
        mutableState.update {
            it.copy(
                version = it.version + 1,
                lastSuccess = operation,
            )
        }
    }

    private fun recordFailure(error: DiagnosticRecorderError) {
        mutableState.update {
            it.copy(
                errorCount = it.errorCount + 1,
                lastError = error,
            )
        }
    }

    private fun failQueuedCommands() {
        while (true) {
            val command = queue.tryReceive().getOrNull() ?: return
            failCommand(command)
        }
    }

    private fun failCommand(command: Command) {
        when (command) {
            is Command.Append -> recordFailure(DiagnosticRecorderError.RECORDER_CLOSED)
            is Command.Page -> failResult(command.result)
            is Command.Clear -> failResult(command.result)
            is Command.ExportSnapshot<*> -> failResult(command.result)
            is Command.Serialized<*> -> failResult(command.result)
        }
    }

    private fun failResult(result: CompletableDeferred<*>) {
        recordFailure(DiagnosticRecorderError.RECORDER_CLOSED)
        result.completeExceptionally(DiagnosticRecorderOperationException())
    }

    private sealed interface Command {
        data class Append(val event: DiagnosticEvent) : Command

        data class Page(
            val beforeId: Long?,
            val limit: Int,
            val result: CompletableDeferred<List<StoredDiagnosticEvent>>,
        ) : Command

        data class Clear(
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class ExportSnapshot<T>(
            val block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
            val result: CompletableDeferred<T>,
        ) : Command

        data class Serialized<T>(
            val block: () -> T,
            val result: CompletableDeferred<T>,
        ) : Command
    }

    private class DiagnosticRecorderOperationException :
        IllegalStateException("Diagnostic storage operation failed")

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
