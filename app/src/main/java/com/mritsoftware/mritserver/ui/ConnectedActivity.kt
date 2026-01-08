package com.mritsoftware.mritserver.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mritsoftware.mritserver.MainActivity
import com.mritsoftware.mritserver.R
import com.mritsoftware.mritserver.adapter.WelcomeDevice
import com.mritsoftware.mritserver.adapter.WelcomeDeviceAdapter
import com.mritsoftware.mritserver.service.PythonServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ConnectedActivity : AppCompatActivity() {
    
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var statusCircle: View
    private lateinit var configButton: com.google.android.material.button.MaterialButton
    private lateinit var changeDeviceButton: com.google.android.material.button.MaterialButton
    private lateinit var healthButton: com.google.android.material.button.MaterialButton
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPreferences = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
        
        // Verificar se já está configurado, se não, voltar para welcome
        if (!isSiteConfigured()) {
            startWelcomeActivity()
            return
        }
        
        setContentView(R.layout.activity_connected)
        
        setupViews()
        setupAnimations()
        startServerService()
        checkServerStatus()
    }
    
    private fun setupViews() {
        statusCircle = findViewById(R.id.statusCircle)
        configButton = findViewById(R.id.configButton)
        changeDeviceButton = findViewById(R.id.changeDeviceButton)
        healthButton = findViewById(R.id.healthButton)
        
        // Configurar botão CONFIG (abre configurações)
        configButton.setOnClickListener {
            val intent = Intent(this, com.mritsoftware.mritserver.ui.SettingsActivity::class.java)
            startActivity(intent)
        }
        
        // Configurar botão de trocar dispositivo (reconfigurar servidor)
        changeDeviceButton.setOnClickListener {
            showChangeDeviceDialog()
        }
        
        // Configurar botão Saúde (status do servidor e cache)
        healthButton.setOnClickListener {
            showHealthDialog()
        }
        
        // Aplicar insets para status bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
    
    private fun showChangeDeviceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_device, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.devicesRecyclerView)
        val progressBar = dialogView.findViewById<View>(R.id.progressBar)
        val emptyText = dialogView.findViewById<TextView>(R.id.emptyText)
        val scanButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.scanButton)
        
        val devices = mutableListOf<WelcomeDevice>()
        // Criar adapter com lista vazia inicial - ele terá sua própria cópia
        val adapter = WelcomeDeviceAdapter(mutableListOf())
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = adapter
        // Desabilitar nested scrolling se necessário
        recyclerView.isNestedScrollingEnabled = false
        
        // Configurar clique no dispositivo ANTES de buscar
        adapter.setOnDeviceClickListener { device ->
            connectNewDevice(device, dialog)
        }
        
        // Buscar dispositivos ao abrir
        scanDevicesForDialog(dialogView, progressBar, emptyText, recyclerView, devices, adapter, dialog)
        
        scanButton.setOnClickListener {
            scanDevicesForDialog(dialogView, progressBar, emptyText, recyclerView, devices, adapter, dialog)
        }
        
        dialog.show()
    }
    
    private fun scanDevicesForDialog(
        dialogView: View,
        progressBar: View,
        emptyText: TextView,
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        devices: MutableList<WelcomeDevice>,
        adapter: WelcomeDeviceAdapter,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.GONE
        }
        
        coroutineScope.launch {
            try {
                // Primeiro verificar se o servidor está rodando
                val serverRunning = withContext(Dispatchers.IO) {
                    try {
                        val healthUrl = URL("http://127.0.0.1:8000/health")
                        val healthConnection = healthUrl.openConnection() as HttpURLConnection
                        healthConnection.requestMethod = "GET"
                        healthConnection.connectTimeout = 3000
                        healthConnection.readTimeout = 3000
                        val healthCode = healthConnection.responseCode
                        healthConnection.disconnect()
                        healthCode == 200
                    } catch (e: Exception) {
                        false
                    }
                }
                
                if (!serverRunning) {
                    android.util.Log.w("ConnectedActivity", "Servidor não está rodando, tentando usar Python diretamente")
                    // Fallback: usar Python diretamente
                    val scanResult = withContext(Dispatchers.IO) {
                        try {
                            // Inicializar Python se necessário
                            if (!com.chaquo.python.Python.isStarted()) {
                                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this@ConnectedActivity))
                            }
                            
                            val python = com.chaquo.python.Python.getInstance()
                            val module = python.getModule("tuya_server")
                            
                            android.util.Log.d("ConnectedActivity", "Chamando scan_devices via Python...")
                            val result = module.callAttr("scan_devices")
                            
                            if (result != null) {
                                val devicesMap = mutableMapOf<String, Map<String, String>>()
                                val pyDict = result.asMap()
                                
                                for ((key, value) in pyDict) {
                                    val deviceId = key.toString()
                                    val deviceInfo = value?.asMap() ?: emptyMap()
                                    
                                    val deviceMap = mutableMapOf<String, String>()
                                    for ((infoKey, infoValue) in deviceInfo) {
                                        deviceMap[infoKey.toString()] = infoValue?.toString() ?: ""
                                    }
                                    devicesMap[deviceId] = deviceMap
                                }
                                android.util.Log.d("ConnectedActivity", "Python retornou ${devicesMap.size} dispositivos")
                                devicesMap
                            } else {
                                android.util.Log.w("ConnectedActivity", "Python retornou null")
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ConnectedActivity", "Erro ao escanear via Python", e)
                            e.printStackTrace()
                            null
                        }
                    }
                    
                    processScanResult(scanResult, devices, adapter, progressBar, emptyText, recyclerView)
                } else {
                    // Usar endpoint HTTP do servidor
                    android.util.Log.d("ConnectedActivity", "Servidor está rodando, usando endpoint HTTP")
                    val scanResult = withContext(Dispatchers.IO) {
                        try {
                            val url = URL("http://127.0.0.1:8000/tuya/devices")
                            val connection = url.openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 40000  // 40 segundos para dar tempo do scan
                            connection.readTimeout = 40000
                            
                            android.util.Log.d("ConnectedActivity", "Fazendo requisição HTTP...")
                            val responseCode = connection.responseCode
                            android.util.Log.d("ConnectedActivity", "Response code: $responseCode")
                            
                            if (responseCode == 200) {
                                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                                val response = reader.readText()
                                reader.close()
                                connection.disconnect()
                                
                                android.util.Log.d("ConnectedActivity", "Response: $response")
                                
                                // Validar se response não está vazio
                                if (response.isBlank()) {
                                    android.util.Log.w("ConnectedActivity", "Resposta vazia do servidor")
                                    return@withContext null
                                }
                                
                                val json = org.json.JSONObject(response)
                                
                                // Usar optBoolean para evitar exceção se "ok" não existir
                                if (json.optBoolean("ok", false)) {
                                    // Usar optJSONArray para evitar exceção se "devices" não existir ou for null
                                    val devicesArray = json.optJSONArray("devices")
                                    
                                    if (devicesArray == null) {
                                        android.util.Log.w("ConnectedActivity", "Campo 'devices' não encontrado ou é null no JSON")
                                        return@withContext null
                                    }
                                    
                                    android.util.Log.d("ConnectedActivity", "Encontrados ${devicesArray.length()} dispositivos no JSON")
                                    
                                    val devicesMap = mutableMapOf<String, Map<String, String>>()
                                    
                                    for (i in 0 until devicesArray.length()) {
                                        try {
                                            val deviceObj = devicesArray.optJSONObject(i)
                                            if (deviceObj != null) {
                                                val deviceId = deviceObj.optString("id", "")
                                                if (deviceId.isNotEmpty()) {
                                                    devicesMap[deviceId] = mapOf(
                                                        "id" to deviceId,
                                                        "ip" to deviceObj.optString("ip", ""),
                                                        "version" to deviceObj.optString("version", "")
                                                    )
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.w("ConnectedActivity", "Erro ao processar dispositivo $i: ${e.message}")
                                        }
                                    }
                                    
                                    // Só retornar se houver dispositivos válidos
                                    if (devicesMap.isNotEmpty()) {
                                        devicesMap
                                    } else {
                                        android.util.Log.w("ConnectedActivity", "Nenhum dispositivo válido encontrado no JSON")
                                        null
                                    }
                                } else {
                                    android.util.Log.w("ConnectedActivity", "JSON retornou ok=false")
                                    null
                                }
                            } else {
                                android.util.Log.e("ConnectedActivity", "Erro HTTP ao buscar dispositivos: $responseCode")
                                val errorStream = connection.errorStream
                                if (errorStream != null) {
                                    val errorReader = java.io.BufferedReader(java.io.InputStreamReader(errorStream))
                                    val errorResponse = errorReader.readText()
                                    errorReader.close()
                                    android.util.Log.e("ConnectedActivity", "Error response: $errorResponse")
                                }
                                connection.disconnect()
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ConnectedActivity", "Erro ao buscar dispositivos via HTTP", e)
                            e.printStackTrace()
                            null
                        }
                    }
                    
                    processScanResult(scanResult, devices, adapter, progressBar, emptyText, recyclerView)
                }
            } catch (e: Exception) {
                android.util.Log.e("ConnectedActivity", "Erro geral ao buscar dispositivos", e)
                e.printStackTrace()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    recyclerView.visibility = View.GONE
                    emptyText.visibility = View.VISIBLE
                    android.widget.Toast.makeText(
                        this@ConnectedActivity,
                        "Erro ao buscar dispositivos: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun processScanResult(
        scanResult: Map<String, Map<String, String>>?,
        devices: MutableList<WelcomeDevice>,
        adapter: WelcomeDeviceAdapter,
        progressBar: View,
        emptyText: TextView,
        recyclerView: androidx.recyclerview.widget.RecyclerView
    ) {
        // Criar uma nova lista para evitar problemas de referência
        val newDevicesList = mutableListOf<WelcomeDevice>()
        
        if (scanResult != null && scanResult.isNotEmpty()) {
            android.util.Log.d("ConnectedActivity", "Processando ${scanResult.size} dispositivos")
            
            // Salvar dispositivos no cache
            try {
                val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(this)
                deviceCacheManager.saveDevicesFromMap(scanResult)
                android.util.Log.d("ConnectedActivity", "Cache salvo com ${scanResult.size} dispositivos")
            } catch (e: Exception) {
                android.util.Log.e("ConnectedActivity", "Erro ao salvar cache: ${e.message}", e)
            }
            
            for ((deviceId, deviceInfo) in scanResult) {
                val ip = deviceInfo["ip"] ?: ""
                val version = deviceInfo["version"] ?: ""
                
                newDevicesList.add(
                    WelcomeDevice(
                        id = deviceId,
                        ip = ip,
                        protocolVersion = version
                    )
                )
            }
            
            // Atualizar UI no thread principal
            runOnUiThread {
                // Atualizar a lista devices ANTES de atualizar o adapter
                devices.clear()
                devices.addAll(newDevicesList)
                
                // Atualizar adapter com uma CÓPIA da lista para evitar problemas de referência
                adapter.updateDevices(devices.toList())
                
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                
                // Forçar layout
                recyclerView.requestLayout()
                recyclerView.invalidate()
                
                android.widget.Toast.makeText(
                    this@ConnectedActivity,
                    "${devices.size} dispositivo(s) encontrado(s)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.util.Log.d("ConnectedActivity", "Nenhum dispositivo encontrado")
            runOnUiThread {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
                android.widget.Toast.makeText(
                    this@ConnectedActivity,
                    "Nenhum dispositivo encontrado na rede. Verifique se os dispositivos estão conectados.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun connectNewDevice(device: WelcomeDevice, dialog: androidx.appcompat.app.AlertDialog) {
        val siteName = sharedPreferences.getString("site_name", "") ?: ""
        if (siteName.isBlank()) {
            android.widget.Toast.makeText(this, "Nome da unidade não encontrado", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        coroutineScope.launch {
            try {
                // Preparar dados para sincronização com apenas o novo dispositivo
                val devicesData = org.json.JSONObject().apply {
                    put(device.id, org.json.JSONObject().apply {
                        put("name", siteName)
                    })
                }
                
                val syncBody = org.json.JSONObject().apply {
                    put("site_id", siteName)
                    put("devices", devicesData)
                }
                
                // Chamar endpoint de sincronização
                val syncResult = withContext(Dispatchers.IO) {
                    syncWithServer(syncBody.toString())
                }
                
                if (syncResult) {
                    // Salvar device_id para heartbeat
                    sharedPreferences.edit()
                        .putString("device_id", device.id)
                        .apply()
                    
                    // Reiniciar heartbeat com novo device
                    com.mritsoftware.mritserver.service.HeartbeatService.startHeartbeat(this@ConnectedActivity)
                    
                    android.widget.Toast.makeText(this@ConnectedActivity, "Dispositivo trocado com sucesso", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    android.widget.Toast.makeText(this@ConnectedActivity, "Erro ao trocar dispositivo", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ConnectedActivity", "Erro ao trocar dispositivo", e)
                android.widget.Toast.makeText(this@ConnectedActivity, "Erro: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun syncWithServer(body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://127.0.0.1:8000/tuya/sync")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            val outputStream = connection.outputStream
            val writer = java.io.OutputStreamWriter(outputStream, "UTF-8")
            writer.write(body)
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = org.json.JSONObject(response)
                connection.disconnect()
                return@withContext json.getBoolean("ok")
            }
            
            connection.disconnect()
            false
        } catch (e: Exception) {
            android.util.Log.e("ConnectedActivity", "Erro ao sincronizar", e)
            false
        }
    }
    
    private fun setupAnimations() {
        // Animação de pulso no círculo
        val pulseAnimator = ObjectAnimator.ofFloat(statusCircle, "scaleX", 1f, 1.1f, 1f)
        pulseAnimator.duration = 2000
        pulseAnimator.repeatCount = ValueAnimator.INFINITE
        pulseAnimator.repeatMode = ValueAnimator.REVERSE
        pulseAnimator.interpolator = LinearInterpolator()
        pulseAnimator.start()
        
        val pulseAnimatorY = ObjectAnimator.ofFloat(statusCircle, "scaleY", 1f, 1.1f, 1f)
        pulseAnimatorY.duration = 2000
        pulseAnimatorY.repeatCount = ValueAnimator.INFINITE
        pulseAnimatorY.repeatMode = ValueAnimator.REVERSE
        pulseAnimatorY.interpolator = LinearInterpolator()
        pulseAnimatorY.start()
    }
    
    private fun startServerService() {
        // Iniciar servidor Python em background
        val intent = Intent(this, PythonServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Iniciar heartbeat worker para ping a cada 15 minutos
        com.mritsoftware.mritserver.service.HeartbeatService.startHeartbeat(this)
    }
    
    private fun checkServerStatus() {
        coroutineScope.launch {
            // Aguardar um pouco para o servidor iniciar
            delay(2000)
            
            while (true) {
                val isConnected = withContext(Dispatchers.IO) {
                    checkServerHealth()
                }
                
                // Atualizar UI baseado no status
                runOnUiThread {
                    updateConnectionStatus(isConnected)
                }
                
                // Verificar novamente após 5 segundos
                delay(5000)
            }
        }
    }
    
    private fun checkServerHealth(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:8000/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
    
    private fun updateConnectionStatus(isConnected: Boolean) {
        // O círculo já está animado, apenas garantir que está visível
        if (isConnected) {
            statusCircle.alpha = 1f
        } else {
            statusCircle.alpha = 0.5f
        }
    }
    
    private fun isSiteConfigured(): Boolean {
        return sharedPreferences.getBoolean("welcome_completed", false) &&
               sharedPreferences.getString("site_name", null) != null
    }
    
    private fun startWelcomeActivity() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun showHealthDialog() {
        coroutineScope.launch {
            try {
                // Verificar status do servidor
                val serverStatus = withContext(Dispatchers.IO) {
                    checkServerHealth()
                }
                
                // Verificar cache
                val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(this@ConnectedActivity)
                val hasCache = deviceCacheManager.hasCache()
                val cachedDevices = deviceCacheManager.loadDevices()
                val lastUpdate = deviceCacheManager.getLastUpdateTimestamp()
                
                // Formatar informações
                val serverStatusText = if (serverStatus) "✅ Online" else "❌ Offline"
                val cacheStatusText = if (hasCache) {
                    val deviceCount = cachedDevices?.size ?: 0
                    val lastUpdateDate = if (lastUpdate > 0) {
                        val date = java.util.Date(lastUpdate)
                        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(date)
                    } else {
                        "Nunca"
                    }
                    "✅ OK ($deviceCount dispositivo(s), atualizado: $lastUpdateDate)"
                } else {
                    "❌ Vazio"
                }
                
                // Mostrar dialog
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@ConnectedActivity)
                        .setTitle("Status do Sistema")
                        .setMessage(
                            "Servidor: $serverStatusText\n\n" +
                            "Cache: $cacheStatusText"
                        )
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Limpar Cache") { _, _ ->
                            deviceCacheManager.clearCache()
                            android.widget.Toast.makeText(
                                this@ConnectedActivity,
                                "Cache limpo com sucesso",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ConnectedActivity", "Erro ao verificar saúde do sistema", e)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@ConnectedActivity,
                        "Erro ao verificar status: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onBackPressed() {
        // Não permitir voltar, apenas minimizar o app
        moveTaskToBack(true)
    }
}

