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
                .setRequiresBatteryNotLow(false) // Não precisa bateria alta
                .setRequiresCharging(false) // Não precisa estar carregando
                .setRequiresDeviceIdle(false) // Pode executar mesmo com tela ligada
                .setRequiresStorageNotLow(false) // Não precisa muito espaço
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
                ExistingPeriodicWorkPolicy.REPLACE, // Substituir se já existir para garantir que está ativo
                heartbeatWork
            )
            
            Log.d(TAG, "Heartbeat worker iniciado: execução imediata + a cada 15 minutos")
            Log.d(TAG, "WorkManager configurado para executar mesmo com app fechado")
            
            // Verificar se WorkManager está funcionando (debug)
            try {
                val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
                if (workInfos.isNotEmpty()) {
                    val workInfo = workInfos[0]
                    Log.d(TAG, "Status do WorkManager: ${workInfo.state}, tentativas: ${workInfo.runAttemptCount}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível verificar status do WorkManager: ${e.message}")
            }
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
