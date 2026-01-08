package com.mritsoftware.mritserver.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mritsoftware.mritserver.worker.HeartbeatWorker
import java.util.concurrent.TimeUnit

/**
 * Gerencia o Worker de heartbeat que atualiza o updated_at no banco a cada 10 minutos
 */
object HeartbeatService {
    
    private const val TAG = "HeartbeatService"
    private const val WORK_NAME = "heartbeat_work"
    
    /**
     * Inicia o worker periódico de heartbeat (a cada 10 minutos)
     */
    fun startHeartbeat(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Não precisa de internet, apenas servidor local
                .build()
            
            // WorkManager requer mínimo de 15 minutos para PeriodicWorkRequest
            // Usaremos 15 minutos (o mais próximo de 10 que é permitido)
            val heartbeatWork = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, // Intervalo mínimo permitido pelo WorkManager
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Manter trabalho existente se já estiver rodando
                heartbeatWork
            )
            
            Log.d(TAG, "Heartbeat worker iniciado (a cada 15 minutos - mínimo permitido pelo WorkManager)")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar heartbeat worker: ${e.message}", e)
        }
    }
    
    /**
     * Para o worker de heartbeat
     */
    fun stopHeartbeat(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Heartbeat worker cancelado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cancelar heartbeat worker: ${e.message}", e)
        }
    }
}
