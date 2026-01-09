package com.mritsoftware.mritserver.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
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
    private val HEARTBEAT_MONITOR_INTERVAL = 20 * 60 * 1000L // 20 minutos - verificar se heartbeat executou
    private var localIpMonitor: LocalIpMonitorService? = null
    private var wasServerOnline = false // Rastrear estado anterior do servidor
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var heartbeatMonitorJob: Job? = null
    
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
        
        // Manter WiFi ativo mesmo quando tela está bloqueada
        acquireWakeLock()
        
        // Manter conexão de rede ativa
        registerNetworkCallback()
        
        // Verificar e solicitar desativação de otimizações de bateria (se necessário)
        checkBatteryOptimizations()
        
        startPythonServer()
        startHealthCheck()
        startLocalIpMonitoring()
        startHeartbeatMonitoring()
        
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
                
                // Verificar conectividade antes de verificar servidor
                val connectivityInfo = getConnectivityInfo()
                Log.d(TAG, "Health check: $connectivityInfo")
                
                val isServerOnline = checkServerHealth()
                
                if (!isServerOnline) {
                    Log.w(TAG, "Servidor não está respondendo - Reiniciando...")
                    Log.w(TAG, "Info de rede: $connectivityInfo")
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
    
    /**
     * Obtém informações detalhadas sobre conectividade para debug
     */
    private fun getConnectivityInfo(): String {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            
            "WiFi=$hasWifi, Ethernet=$hasEthernet, Internet=$hasInternet, Validada=$isValidated"
        } catch (e: Exception) {
            "Erro ao obter info: ${e.message}"
        }
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
            // Verificar conectividade WiFi antes de testar servidor
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            
            Log.d(TAG, "Health check: WiFi=$hasWifi, Internet=$hasInternet, Validada=$isValidated")
            
            if (!hasWifi && !hasInternet) {
                Log.w(TAG, "Health check: WiFi não está conectado")
                return false
            }
            
            val url = java.net.URL("http://127.0.0.1:8000/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000 // Aumentar timeout para redes mais lentas
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Health check: Servidor OK (WiFi conectado, rede validada)")
                true
            } else {
                Log.w(TAG, "Health check: Servidor retornou código $responseCode (WiFi: $hasWifi, Internet: $hasInternet)")
                false
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Health check: Timeout ao conectar (rede pode estar lenta ou bloqueada)", e)
            false
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Health check: Erro de conexão (servidor pode estar offline ou rede bloqueada)", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Health check: Erro ao verificar servidor: ${e.javaClass.simpleName} - ${e.message}", e)
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
    
    /**
     * Adquire WakeLock para manter WiFi ativo mesmo quando tela está bloqueada
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MRITServer::WakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L /*10 horas*/) // Renovar a cada 10 horas
            }
            Log.d(TAG, "WakeLock adquirido - WiFi será mantido ativo")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adquirir WakeLock: ${e.message}", e)
        }
    }
    
    /**
     * Registra NetworkCallback para manter conexão de rede ativa
     */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Rede WiFi disponível - Mantendo conexão ativa")
                        // NetworkCallback já mantém a conexão ativa automaticamente
                    }
                    
                    override fun onLost(network: Network) {
                        Log.w(TAG, "Rede WiFi perdida - Tentando reconectar...")
                        // NetworkCallback detectará automaticamente quando nova rede estiver disponível
                    }
                    
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        Log.d(TAG, "Capacidades da rede mudaram - Internet: $hasInternet, Validada: $isValidated")
                    }
                }
                
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
                Log.d(TAG, "NetworkCallback registrado - Conexão será mantida ativa")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao registrar NetworkCallback: ${e.message}", e)
            }
        }
    }
    
    /**
     * Libera WakeLock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock liberado")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar WakeLock: ${e.message}", e)
        }
    }
    
    /**
     * Remove NetworkCallback
     */
    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                networkCallback?.let {
                    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    connectivityManager.unregisterNetworkCallback(it)
                    Log.d(TAG, "NetworkCallback removido")
                }
                networkCallback = null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover NetworkCallback: ${e.message}", e)
            }
        }
    }
    
    /**
     * Verifica se otimizações de bateria estão ativas e loga aviso
     */
    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = packageName
                val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
                
                if (!isIgnoringBatteryOptimizations) {
                    Log.w(TAG, "⚠️ Otimizações de bateria NÃO estão desativadas")
                    Log.w(TAG, "⚠️ O Android pode matar o processo em background")
                    Log.w(TAG, "⚠️ Para melhor funcionamento, desative otimizações de bateria nas configurações")
                } else {
                    Log.d(TAG, "✅ Otimizações de bateria estão desativadas")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar otimizações de bateria: ${e.message}", e)
            }
        }
    }
    
    /**
     * Monitora se o heartbeat está sendo executado corretamente
     * Se detectar que não executou há muito tempo, força uma execução
     */
    private fun startHeartbeatMonitoring() {
        heartbeatMonitorJob?.cancel()
        
        heartbeatMonitorJob = coroutineScope.launch {
            while (isActive) {
                delay(HEARTBEAT_MONITOR_INTERVAL)
                
                try {
                    // Verificar último heartbeat através do SharedPreferences
                    val prefs = getSharedPreferences("TuyaGateway", Context.MODE_PRIVATE)
                    val lastHeartbeatTime = prefs.getLong("last_heartbeat_time", 0L)
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastRun = currentTime - lastHeartbeatTime
                    
                    // Verificar status do WorkManager também
                    val workManager = androidx.work.WorkManager.getInstance(this@PythonServerService)
                    val workInfos = workManager.getWorkInfosForUniqueWork("heartbeat_work").get()
                    
                    if (workInfos.isNotEmpty()) {
                        val workInfo = workInfos[0]
                        val minutesSinceLastRun = timeSinceLastRun / 1000 / 60
                        
                        // Se não executou há mais de 20 minutos E não está rodando, forçar execução
                        if (timeSinceLastRun > HEARTBEAT_MONITOR_INTERVAL && 
                            workInfo.state != androidx.work.WorkInfo.State.RUNNING &&
                            workInfo.state != androidx.work.WorkInfo.State.ENQUEUED) {
                            Log.w(TAG, "⚠️ Heartbeat não executou há $minutesSinceLastRun minutos (status: ${workInfo.state}) - Forçando execução...")
                            HeartbeatService.startHeartbeat(this@PythonServerService)
                        } else if (lastHeartbeatTime > 0) {
                            Log.d(TAG, "✅ Heartbeat está funcionando (última execução há $minutesSinceLastRun minutos, status: ${workInfo.state})")
                        } else {
                            Log.d(TAG, "ℹ️ Heartbeat ainda não executou pela primeira vez (status: ${workInfo.state})")
                        }
                    } else {
                        Log.w(TAG, "⚠️ Nenhum trabalho de heartbeat encontrado no WorkManager - Reiniciando...")
                        HeartbeatService.startHeartbeat(this@PythonServerService)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao monitorar heartbeat: ${e.message}", e)
                }
            }
        }
        
        Log.d(TAG, "Monitoramento de heartbeat iniciado (verifica a cada ${HEARTBEAT_MONITOR_INTERVAL / 1000 / 60} minutos)")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Liberar WakeLock
        releaseWakeLock()
        
        // Remover NetworkCallback
        unregisterNetworkCallback()
        
        // Parar health check
        healthCheckJob?.cancel()
        healthCheckJob = null
        
        // Parar monitoramento de heartbeat
        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = null
        
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

