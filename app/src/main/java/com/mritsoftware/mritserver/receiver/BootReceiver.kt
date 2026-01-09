package com.mritsoftware.mritserver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.mritsoftware.mritserver.MainActivity
import com.mritsoftware.mritserver.service.PythonServerService

class BootReceiver : BroadcastReceiver() {
    
    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive chamado com action: ${intent.action}")
        
        // Verificar múltiplas ações de boot
        val isBootAction = intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED
        
        if (isBootAction) {
            Log.d(TAG, "=== BOOT COMPLETADO DETECTADO ===")
            Log.d(TAG, "Package: ${context.packageName}")
            Log.d(TAG, "Android Version: ${Build.VERSION.SDK_INT}")
            
            // IMPORTANTE: Iniciar o serviço IMEDIATAMENTE, sem goAsync
            // goAsync pode não funcionar em algumas versões do Android
            try {
                Log.d(TAG, "Iniciando PythonServerService...")
                val serviceIntent = Intent(context, PythonServerService::class.java).apply {
                    setPackage(context.packageName)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(serviceIntent)
                        Log.d(TAG, "startForegroundService chamado com sucesso")
                    } catch (e: IllegalStateException) {
                        // Se falhar, tentar startService
                        Log.w(TAG, "startForegroundService falhou, tentando startService: ${e.message}")
                        context.startService(serviceIntent)
                    }
                } else {
                    context.startService(serviceIntent)
                    Log.d(TAG, "startService chamado com sucesso")
                }
                
                // Aguardar um pouco e iniciar heartbeat
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d(TAG, "Iniciando heartbeat após boot...")
                        com.mritsoftware.mritserver.service.HeartbeatService.startHeartbeat(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao iniciar heartbeat após boot: ${e.message}", e)
                    }
                }, 10000) // Aguardar 10 segundos para servidor iniciar
            } catch (e: Exception) {
                Log.e(TAG, "ERRO ao iniciar serviço: ${e.message}", e)
                e.printStackTrace()
            }
            
            // Usar Handler para abrir apps depois
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    // Abrir mritserver
                    val mritserverIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        setPackage(context.packageName)
                    }
                    context.startActivity(mritserverIntent)
                    Log.d(TAG, "MainActivity (mritserver) aberta com sucesso")
                    
                    // Aguardar mais 2 segundos antes de abrir gelafitgo
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            val gelafitgoIntent = context.packageManager.getLaunchIntentForPackage("com.mrit.gelafitgo")
                            if (gelafitgoIntent != null) {
                                gelafitgoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                gelafitgoIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                context.startActivity(gelafitgoIntent)
                                Log.d(TAG, "Aplicativo gelafitgo aberto com sucesso")
                            } else {
                                Log.w(TAG, "Aplicativo gelafitgo não encontrado ou não instalado")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Não foi possível abrir gelafitgo: ${e.message}", e)
                        }
                    }, 2000)
                } catch (e: Exception) {
                    Log.w(TAG, "Não foi possível abrir mritserver: ${e.message}", e)
                }
            }, 5000) // Aguardar 5 segundos para o servidor iniciar
        } else {
            Log.d(TAG, "Action não reconhecido: ${intent.action}")
        }
    }
}

