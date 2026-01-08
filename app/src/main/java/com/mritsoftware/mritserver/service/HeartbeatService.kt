package com.mritsoftware.mritserver.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mritsoftware.mritserver.worker.HeartbeatWorker
import java.util.concurrent.TimeUnit

/**
 * Gerencia o Worker de heartbeat que atualiza o servidor_online no banco a cada 15 minutos
 */
object HeartbeatService {
    
    private const val TAG = "HeartbeatService"
    private const val WORK_NAME = "heartbeat_work"
    
    /**
     * Inicia o worker periódico de heartbeat (a cada 15 minutos)
     * Atualiza o campo servidor_online no banco para indicar que o servidor está online
     */
    fun startHeartbeat(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Não precisa de internet, apenas servidor local
                .build()
            
            // Executar heartbeat IMEDIATAMENTE ao sincronizar
            val immediateHeartbeat = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(constraints)
                .addTag("${WORK_NAME}_immediate")
                .build()
            
            // Agendar heartbeat periódico a cada 15 minutos
            // WorkManager requer mínimo de 15 minutos para PeriodicWorkRequest
            val heartbeatWork = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, // Intervalo mínimo permitido pelo WorkManager
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            
            // Executar imediatamente
            WorkManager.getInstance(context).enqueue(immediateHeartbeat)
            
            // Agendar periódico
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Manter trabalho existente se já estiver rodando
                heartbeatWork
            )
            
            Log.d(TAG, "Heartbeat worker iniciado: execução imediata + a cada 15 minutos")
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
