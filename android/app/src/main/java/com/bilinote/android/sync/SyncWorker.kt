package com.bilinote.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.bilinote.android.AppDependencies
import com.bilinote.android.network.ApiHttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            withResource(
                create = { AppDependencies(applicationContext) },
                close = AppDependencies::close,
            ) { dependencies ->
                val settings = dependencies.settingsRepository.observeSettings.first()
                val configuration = dependencies.configuredBackend(settings)
                configuration.backend?.let { backend ->
                    runSyncWithPolicy { backend.syncActiveSnapshots() }
                } ?: Result.failure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

internal suspend fun runSyncWithPolicy(sync: suspend () -> Unit): ListenableWorker.Result {
    return try {
        sync()
        ListenableWorker.Result.success()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: ApiHttpException) {
        if (error.statusCode >= 500) ListenableWorker.Result.retry()
        else ListenableWorker.Result.failure()
    } catch (_: IOException) {
        ListenableWorker.Result.retry()
    } catch (_: Exception) {
        ListenableWorker.Result.failure()
    }
}

internal suspend fun <R, T> withResource(
    create: () -> R,
    close: (R) -> Unit,
    block: suspend (R) -> T,
): T {
    val resource = create()
    var blockFailure: Throwable? = null
    return try {
        block(resource)
    } catch (failure: Throwable) {
        blockFailure = failure
        throw failure
    } finally {
        try {
            close(resource)
        } catch (closeFailure: Throwable) {
            blockFailure?.addSuppressed(closeFailure) ?: throw closeFailure
        }
    }
}
