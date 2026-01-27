package com.mritsoftware.mritserver.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mritsoftware.mritserver.receiver.AlarmManagerHeartbeatReceiver
import com.mritsoftware.mritserver.worker.HeartbeatWorker
import java.util.concurrent.TimeUnit

/**
 * Gerencia o Worker de heartbeat que atualiza o servidor_online no banco a cada 15 minutos
 * Inclui múltiplas camadas de proteção:
 * 1. WorkManager (principal)
 * 2. AlarmManager (fallback)
 * 3. Heartbeat direto no serviço (fallback adicional)
 */
object HeartbeatService {
    
    private const val TAG = "HeartbeatService"
    private const val WORK_NAME = "heartbeat_work"
    private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutos
    private const val ALARM_REQUEST_CODE = 1001
    
    /**
     * Verifica se está na mesma rede local (WiFi conectado)
     */
    private fun isOnLocalNetwork(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasEthernet = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            // Considerar na mesma rede se WiFi ou Ethernet conectado
            hasWifi || hasEthernet
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar rede local: ${e.message}")
            false
        }
    }
    
    /**
     * Inicia o worker periódico de heartbeat (a cada 15 minutos)
     * Atualiza o campo servidor_online no banco para indicar que o servidor está online
     * Inclui múltiplas camadas de proteção para garantir execução
     */
    fun startHeartbeat(context: Context) {
        val isLocalNetwork = isOnLocalNetwork(context)
        Log.d(TAG, "Iniciando heartbeat (rede local: $isLocalNetwork)")
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Não precisa de internet, apenas servidor local
                .setRequiresBatteryNotLow(false) // Não precisa bateria alta
                .setRequiresCharging(false) // Não precisa estar carregando
                .setRequiresDeviceIdle(false) // Pode executar mesmo com tela ligada
                .setRequiresStorageNotLow(false) // Não precisa muito espaço
                .build()
            
            // Executar heartbeat IMEDIATAMENTE ao sincronizar
            // Usar backoff exponencial para retry automático
            val immediateHeartbeat = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15, // Começar com 15 segundos
                    TimeUnit.SECONDS
                )
                .addTag("${WORK_NAME}_immediate")
                .build()
            
            // Agendar heartbeat periódico a cada 15 minutos
            // WorkManager requer mínimo de 15 minutos para PeriodicWorkRequest
            // Usar backoff exponencial para retry automático
            val heartbeatWork = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, // Intervalo mínimo permitido pelo WorkManager
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15, // Começar com 15 segundos
                    TimeUnit.SECONDS
                )
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
            
            // CAMADA 2: AlarmManager como fallback adicional (especialmente importante na mesma rede)
            if (isLocalNetwork) {
                startAlarmManagerHeartbeat(context)
                Log.d(TAG, "✅ AlarmManager iniciado como fallback (rede local detectada)")
            } else {
                // Mesmo sem rede local, iniciar AlarmManager como proteção
                startAlarmManagerHeartbeat(context)
                Log.d(TAG, "✅ AlarmManager iniciado como fallback")
            }
            
            // Verificar se WorkManager está funcionando (debug)
            try {
                val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
                if (workInfos.isNotEmpty()) {
                    val workInfo = workInfos[0]
                    Log.d(TAG, "Status do WorkManager: ${workInfo.state}, tentativas: ${workInfo.runAttemptCount}")
                    
                    // Se o trabalho está bloqueado ou enfileirado, logar aviso
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.BLOCKED -> {
                            Log.w(TAG, "⚠️ WorkManager está BLOQUEADO - pode não executar")
                        }
                        androidx.work.WorkInfo.State.ENQUEUED -> {
                            Log.d(TAG, "✅ WorkManager está ENFILEIRADO - aguardando condições")
                        }
                        androidx.work.WorkInfo.State.RUNNING -> {
                            Log.d(TAG, "🔄 WorkManager está EXECUTANDO")
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            Log.d(TAG, "✅ WorkManager executou com SUCESSO")
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            Log.e(TAG, "❌ WorkManager FALHOU")
                        }
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            Log.w(TAG, "⚠️ WorkManager foi CANCELADO")
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ Nenhum trabalho encontrado no WorkManager")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível verificar status do WorkManager: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar heartbeat worker: ${e.message}", e)
        }
    }
    
    /**
     * Inicia AlarmManager como fallback do WorkManager
     * Garante execução mesmo se WorkManager falhar
     */
    private fun startAlarmManagerHeartbeat(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "AlarmManager não disponível")
                return
            }
            
            val intent = Intent(context, AlarmManagerHeartbeatReceiver::class.java).apply {
                action = AlarmManagerHeartbeatReceiver.ACTION_HEARTBEAT_ALARM
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Cancelar alarme anterior se existir
            alarmManager.cancel(pendingIntent)
            
            // Agendar alarme periódico
            val triggerTime = System.currentTimeMillis() + ALARM_INTERVAL_MS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+: Usar setExactAndAllowWhileIdle para máxima confiabilidade
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "AlarmManager agendado (setExactAndAllowWhileIdle) para ${ALARM_INTERVAL_MS / 1000 / 60} minutos")
                } catch (e: Exception) {
                    // Fallback para setExact se setExactAndAllowWhileIdle falhar
                    Log.w(TAG, "setExactAndAllowWhileIdle falhou, usando setExact: ${e.message}")
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                // Android < 6.0: Usar setRepeating (mais confiável em versões antigas)
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    ALARM_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "AlarmManager agendado (setRepeating) para ${ALARM_INTERVAL_MS / 1000 / 60} minutos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar AlarmManager: ${e.message}", e)
        }
    }
    
    /**
     * Agenda próximo alarme do AlarmManager
     * Chamado após cada execução para manter o ciclo
     */
    fun scheduleNextAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                return
            }
            
            val intent = Intent(context, AlarmManagerHeartbeatReceiver::class.java).apply {
                action = AlarmManagerHeartbeatReceiver.ACTION_HEARTBEAT_ALARM
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = System.currentTimeMillis() + ALARM_INTERVAL_MS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } catch (e: Exception) {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao agendar próximo alarme: ${e.message}", e)
        }
    }
    
    /**
     * Para o worker de heartbeat e cancela AlarmManager
     */
    fun stopHeartbeat(context: Context) {
        try {
            // Parar WorkManager
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Heartbeat worker cancelado")
            
            // Cancelar AlarmManager
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, AlarmManagerHeartbeatReceiver::class.java).apply {
                    action = AlarmManagerHeartbeatReceiver.ACTION_HEARTBEAT_ALARM
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                Log.d(TAG, "AlarmManager cancelado")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cancelar heartbeat: ${e.message}", e)
        }
    }
}
