package com.mritsoftware.mritserver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mritsoftware.mritserver.service.HeartbeatService

/**
 * Receiver para AlarmManager - Fallback adicional do WorkManager
 * Garante que heartbeat execute mesmo se WorkManager falhar
 */
class AlarmManagerHeartbeatReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AlarmHeartbeatReceiver"
        const val ACTION_HEARTBEAT_ALARM = "com.mritsoftware.mritserver.HEARTBEAT_ALARM"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_HEARTBEAT_ALARM) {
            Log.d(TAG, "🔄 AlarmManager disparou heartbeat (fallback do WorkManager)")
            
            try {
                // Disparar heartbeat imediato via WorkManager
                // Isso garante que mesmo se WorkManager não agendar, o AlarmManager força execução
                HeartbeatService.startHeartbeat(context)
                
                // Agendar próximo alarme para manter o ciclo
                HeartbeatService.scheduleNextAlarm(context)
                
                Log.d(TAG, "✅ Heartbeat disparado via AlarmManager e próximo alarme agendado")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao disparar heartbeat via AlarmManager: ${e.message}", e)
            }
        }
    }
}
