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
import androidx.core.app.NotificationManagerCompat
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
    private val HEARTBEAT_DIRECT_INTERVAL = 15 * 60 * 1000L // 15 minutos - heartbeat direto no serviço
    private val HEARTBEAT_LOCAL_NETWORK_INTERVAL = 10 * 60 * 1000L // 10 minutos - heartbeat mais frequente quando na mesma rede
    private var localIpMonitor: LocalIpMonitorService? = null
    private var wasServerOnline = false // Rastrear estado anterior do servidor
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var heartbeatMonitorJob: Job? = null
    private var heartbeatDirectJob: Job? = null // Heartbeat direto no serviço como fallback
    
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
        startDirectHeartbeat() // Heartbeat direto no serviço como fallback
        
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
     * Verifica se otimizações de bateria estão ativas e solicita desativação
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
                    
                    // Verificar se já solicitamos antes (evitar spam)
                    val prefs = getSharedPreferences("TuyaGateway", Context.MODE_PRIVATE)
                    val lastRequestTime = prefs.getLong("battery_opt_request_time", 0L)
                    val currentTime = System.currentTimeMillis()
                    val hoursSinceLastRequest = (currentTime - lastRequestTime) / (1000 * 60 * 60)
                    
                    // Solicitar apenas uma vez por dia
                    if (hoursSinceLastRequest >= 24) {
                        Log.d(TAG, "Solicitando desativação de otimizações de bateria...")
                        requestBatteryOptimizationDisable()
                        prefs.edit().putLong("battery_opt_request_time", currentTime).apply()
                    } else {
                        Log.d(TAG, "Já solicitamos desativação há ${hoursSinceLastRequest.toInt()} horas, aguardando...")
                    }
                } else {
                    Log.d(TAG, "✅ Otimizações de bateria estão desativadas")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar otimizações de bateria: ${e.message}", e)
            }
        }
    }
    
    /**
     * Solicita ao usuário para desativar otimizações de bateria
     */
    private fun requestBatteryOptimizationDisable() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            // Criar notificação para guiar o usuário
            val notificationManager = NotificationManagerCompat.from(this)
            if (notificationManager.areNotificationsEnabled()) {
                val notificationIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("⚠️ Otimizações de Bateria Ativas")
                    .setContentText("Para melhor funcionamento, desative otimizações de bateria")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
                
                notificationManager.notify(NOTIFICATION_ID + 1, notification)
                Log.d(TAG, "Notificação de otimizações de bateria enviada")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao solicitar desativação de otimizações: ${e.message}", e)
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
    
    /**
     * Verifica se está na mesma rede local (WiFi ou Ethernet conectado)
     */
    private fun isOnLocalNetwork(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            hasWifi || hasEthernet
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar rede local: ${e.message}")
            false
        }
    }
    
    /**
     * Executa heartbeat diretamente no serviço
     * Quando na mesma rede: a cada 10 minutos (mais frequente)
     * Quando não na mesma rede: a cada 15 minutos (padrão)
     * Isso funciona como fallback caso o WorkManager e AlarmManager não executem
     */
    private fun startDirectHeartbeat() {
        heartbeatDirectJob?.cancel()
        
        heartbeatDirectJob = coroutineScope.launch {
            // Aguardar um pouco antes da primeira execução
            delay(HEARTBEAT_DIRECT_INTERVAL)
            
            while (isActive) {
                try {
                    val isLocalNetwork = isOnLocalNetwork()
                    val interval = if (isLocalNetwork) {
                        HEARTBEAT_LOCAL_NETWORK_INTERVAL
                    } else {
                        HEARTBEAT_DIRECT_INTERVAL
                    }
                    
                    Log.d(TAG, "🔄 Heartbeat direto no serviço executando (fallback, rede local: $isLocalNetwork)...")
                    
                    // Verificar se servidor está rodando
                    if (checkServerHealth()) {
                        // Executar heartbeat diretamente com retry mais agressivo se na mesma rede
                        if (isLocalNetwork) {
                            executeDirectHeartbeatWithRetry(maxAttempts = 5) // Mais tentativas na mesma rede
                        } else {
                            executeDirectHeartbeat()
                        }
                    } else {
                        Log.w(TAG, "Servidor não está respondendo, pulando heartbeat direto")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao executar heartbeat direto: ${e.message}", e)
                }
                
                // Usar intervalo dinâmico baseado na rede
                val currentInterval = if (isOnLocalNetwork()) {
                    HEARTBEAT_LOCAL_NETWORK_INTERVAL
                } else {
                    HEARTBEAT_DIRECT_INTERVAL
                }
                delay(currentInterval)
            }
        }
        
        Log.d(TAG, "Heartbeat direto no serviço iniciado (intervalo dinâmico: 10min rede local, 15min padrão)")
    }
    
    /**
     * Executa heartbeat diretamente com retry agressivo (para mesma rede)
     */
    private suspend fun executeDirectHeartbeatWithRetry(maxAttempts: Int = 5) {
        var attempts = 0
        var success = false
        
        while (!success && attempts < maxAttempts) {
            attempts++
            Log.d(TAG, "Tentativa $attempts/$maxAttempts de heartbeat direto (rede local)...")
            
            success = executeDirectHeartbeat()
            
            if (!success && attempts < maxAttempts) {
                val delayMs = (attempts * 2000).toLong() // Backoff: 2s, 4s, 6s, 8s
                Log.w(TAG, "Falha na tentativa $attempts, aguardando ${delayMs}ms antes de tentar novamente...")
                kotlinx.coroutines.delay(delayMs)
            }
        }
        
        if (success) {
            Log.d(TAG, "✅ Heartbeat direto enviado com sucesso após $attempts tentativa(s)")
        } else {
            Log.e(TAG, "❌ Falha ao enviar heartbeat direto após $maxAttempts tentativas")
        }
    }
    
    /**
     * Executa heartbeat diretamente (sem WorkManager)
     */
    private suspend fun executeDirectHeartbeat(): Boolean {
        try {
            val prefs = getSharedPreferences("TuyaGateway", Context.MODE_PRIVATE)
            
            // Buscar device_id
            var deviceId = prefs.getString("heartbeat_device_id", null)
            if (deviceId.isNullOrEmpty()) {
                deviceId = prefs.getString("device_id", null)
            }
            
            if (deviceId.isNullOrEmpty()) {
                // Tentar buscar do cache
                val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(this)
                val cachedDevices = deviceCacheManager.loadDevicesAsMap()
                if (cachedDevices != null && cachedDevices.isNotEmpty()) {
                    deviceId = cachedDevices.keys.firstOrNull()
                }
            }
            
            if (deviceId.isNullOrEmpty()) {
                Log.w(TAG, "Device ID não encontrado, pulando heartbeat direto")
                return false
            }
            
            // Enviar heartbeat diretamente
            val url = java.net.URL("http://127.0.0.1:8000/tuya/heartbeat")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val jsonBody = org.json.JSONObject().apply {
                put("tuya_device_id", deviceId)
            }
            
            val writer = java.io.OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            
            if (responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                try {
                    val json = org.json.JSONObject(response)
                    val ok = json.optBoolean("ok", false)
                    if (ok) {
                        // Salvar timestamp
                        prefs.edit()
                            .putString("heartbeat_device_id", deviceId)
                            .putLong("last_heartbeat_time", System.currentTimeMillis())
                            .apply()
                        Log.d(TAG, "✅ Heartbeat direto enviado com sucesso para device $deviceId")
                        connection.disconnect()
                        return true
                    } else {
                        val error = json.optString("error", "Erro desconhecido")
                        Log.w(TAG, "Servidor retornou ok=false no heartbeat direto: $error")
                        connection.disconnect()
                        return false
                    }
                } catch (e: Exception) {
                    // Se resposta não for JSON válido, mas código foi 200, considerar sucesso
                    prefs.edit()
                        .putString("heartbeat_device_id", deviceId)
                        .putLong("last_heartbeat_time", System.currentTimeMillis())
                        .apply()
                    Log.d(TAG, "✅ Heartbeat direto enviado (resposta não-JSON, mas código 200)")
                    connection.disconnect()
                    return true
                }
            } else {
                Log.w(TAG, "Erro HTTP ao enviar heartbeat direto: $responseCode")
                connection.disconnect()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar heartbeat direto: ${e.message}", e)
            return false
        }
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
        
        // Parar heartbeat direto
        heartbeatDirectJob?.cancel()
        heartbeatDirectJob = null
        
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

