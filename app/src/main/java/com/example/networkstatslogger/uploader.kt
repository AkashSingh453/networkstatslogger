package com.example.networkstatslogger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class FirebaseUploader(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val CHUNK_SIZE = 100 // Process 100 logs at a time
    }

    override suspend fun doWork(): Result {
        val logDao = AppDatabase.getDatabase(applicationContext).networkLogDao()
        val firestore = Firebase.firestore

        return try {
            while (true) {
                // Get a small chunk of the oldest logs
                val logsToUpload = logDao.getOldestLogs(CHUNK_SIZE)

                // If there are no more logs to upload, we're done
                if (logsToUpload.isEmpty()) {
                    break
                }

                // Create a batch write for the current chunk
                val batch = firestore.batch()
                logsToUpload.forEach { log ->
                    val docRef = firestore.collection("network_logs").document()
                    batch.set(docRef, log)
                }
                batch.commit().await()

                // If the upload is successful, delete this chunk from the local DB
                val logIds = logsToUpload.map { it.id }
                logDao.deleteLogsByIds(logIds)
            }
            Result.success()
        } catch (e: Exception) {
            // If any part fails, retry the whole process later
            Result.retry()
        }
    }
}
