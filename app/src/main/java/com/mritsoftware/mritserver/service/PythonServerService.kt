package com.mritsoftware.mritserver.service

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mritsoftware.mritserver.MainActivity
import com.mritsoftware.mritserver.R
import com.mritsoftware.mritserver.ui.ConnectedActivity
import com.mritsoftware.mritserver.service.HeartbeatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class PythonServerService : Service() {
    
    private val TAG = "PythonServerService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "tuya_server_channel"
    private var pythonThread: Thread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var healthCheckJob: Job? = null
    private val HEALTH_CHECK_INTERVAL = 60000L // 1 minuto
    private var localIpMonitor: LocalIpMonitorService? = null
    private var wasServerOnline = false // Rastrear estado anterior do servidor
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service criado")
        createNotificationChannel()
        
        // Inicializar Python se ainda não foi inicializado
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
            Log.d(TAG, "Python inicializado")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Verificar se é uma ação de parar o serviço
        if (intent?.action == "STOP_SERVICE") {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Iniciar como foreground service para rodar em background
        startForeground(NOTIFICATION_ID, createNotification())
        startPythonServer()
        startHealthCheck()
        startLocalIpMonitoring()
        
        // Iniciar heartbeat quando serviço inicia (garantir que funcione em background)
        try {
            Log.d(TAG, "Iniciando heartbeat do serviço...")
            HeartbeatService.startHeartbeat(this)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar heartbeat no serviço: ${e.message}", e)
        }
        
        return START_STICKY // Serviço será reiniciado se for morto
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW pode fazer o sistema matar o serviço
            // IMPORTANCE_LOW = sem som, sem vibração, mas visível
            // IMPORTANCE_MIN = pode ser ocultado pelo sistema
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servidor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servidor rodando em background"
                setShowBadge(false)
                // Não permitir que o sistema oculte a notificação
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        // Verificar se já está configurado para abrir a tela correta
        val prefs = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
        val isConfigured = prefs.getBoolean("welcome_completed", false) &&
                          prefs.getString("site_name", null) != null
        
        val intent = if (isConfigured) {
            Intent(this, ConnectedActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MRIT Server")
            .setContentText("Servidor rodando na porta 8000")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Não pode ser removida pelo usuário
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    private fun startPythonServer() {
        // Parar servidor anterior se existir
        stopServer()
        
        // Atualizar site_name se necessário
        updateSiteName()
        
        pythonThread = Thread {
            try {
                val python = Python.getInstance()
                val module = python.getModule("tuya_server")
                
                Log.d(TAG, "Iniciando servidor Flask Python...")
                
                // Iniciar servidor Flask em thread separada
                module.callAttr("start_server", "0.0.0.0", 8000)
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar servidor Python", e)
            }
        }
        
        pythonThread?.start()
        Log.d(TAG, "Thread do servidor Python iniciada")
    }
    
    private fun stopServer() {
        pythonThread?.interrupt()
        try {
            pythonThread?.join(2000) // Aguardar até 2 segundos
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao aguardar thread parar", e)
        }
        pythonThread = null
        Log.d(TAG, "Servidor Python parado")
    }
    
    private fun startHealthCheck() {
        // Cancelar health check anterior se existir
        healthCheckJob?.cancel()
        
        // Inicializar estado como false para detectar primeira vez que servidor fica online
        wasServerOnline = false
        
        healthCheckJob = coroutineScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL)
                
                val isServerOnline = checkServerHealth()
                
                if (!isServerOnline) {
                    Log.w(TAG, "Servidor não está respondendo - Reiniciando...")
                    startPythonServer()
                    wasServerOnline = false
                } else {
                    // Servidor está online
                    // Se estava offline antes e agora está online, disparar heartbeat imediato
                    if (!wasServerOnline) {
                        Log.d(TAG, "Servidor voltou a ficar online - Disparando heartbeat imediato...")
                        triggerImmediateHeartbeat()
                    }
                    wasServerOnline = true
                }
            }
        }
        Log.d(TAG, "Health check iniciado (intervalo: ${HEALTH_CHECK_INTERVAL}ms)")
    }
    
    private fun triggerImmediateHeartbeat() {
        try {
            // Aguardar um pouco para garantir que o servidor está totalmente pronto
            coroutineScope.launch {
                delay(2000) // 2 segundos
                Log.d(TAG, "Disparando heartbeat imediato após servidor voltar online...")
                HeartbeatService.startHeartbeat(this@PythonServerService)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao disparar heartbeat imediato: ${e.message}", e)
        }
    }
    
    private fun checkServerHealth(): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:8000/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Health check: Servidor OK")
                true
            } else {
                Log.w(TAG, "Health check: Servidor retornou código $responseCode")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check: Servidor não está respondendo", e)
            false
        }
    }
    
    private fun updateSiteName() {
        try {
            val prefs = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
            val siteName = prefs.getString("site_name", "ANDROID_DEVICE") ?: "ANDROID_DEVICE"
            
            val python = Python.getInstance()
            val module = python.getModule("tuya_server")
            
            // Garantir que config existe
            module.callAttr("create_config_if_needed")
            
            // Atualizar site_name
            module.callAttr("update_site_name", siteName)
            
            Log.d(TAG, "Site name configurado: $siteName")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar site name", e)
        }
    }
    
    private fun startLocalIpMonitoring() {
        localIpMonitor = LocalIpMonitorService(this)
        localIpMonitor?.startMonitoring()
        Log.d(TAG, "Monitoramento de IP local iniciado")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Parar health check
        healthCheckJob?.cancel()
        healthCheckJob = null
        
        // Parar monitoramento de IP
        localIpMonitor?.stopMonitoring()
        localIpMonitor = null
        
        // Parar thread do servidor
        stopServer()
        Log.d(TAG, "Service destruído")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    fun isServerRunning(): Boolean {
        return pythonThread?.isAlive == true
    }
}

