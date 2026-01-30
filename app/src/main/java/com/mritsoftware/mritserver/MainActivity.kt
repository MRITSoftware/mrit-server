package com.mritsoftware.mritserver

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mritsoftware.mritserver.adapter.DeviceAdapter
import com.mritsoftware.mritserver.model.TuyaDevice
import com.mritsoftware.mritserver.service.PythonServerService
import com.mritsoftware.mritserver.ui.SettingsActivity
import com.mritsoftware.mritserver.ui.DeviceDiscoveryActivity
import com.mritsoftware.mritserver.ui.DeviceDetailsActivity
import com.mritsoftware.mritserver.util.BatteryOptimizationHelper
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var gatewayStatus: TextView
    private lateinit var deviceCount: TextView
    private lateinit var refreshButton: MaterialButton
    
    private val devices = mutableListOf<TuyaDevice>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupToolbar()
        setupViews()
        setupRecyclerView()
        setupListeners()
        
        // Verificar proteção contra otimizações de bateria
        checkBatteryOptimizations()
        
        startServerService()
        // Aguardar servidor iniciar antes de carregar dispositivos
        coroutineScope.launch {
            kotlinx.coroutines.delay(3000) // Dar tempo para o servidor Python iniciar
            refreshDevices()
        }
    }
    
    private fun checkBatteryOptimizations() {
        // Verificar se precisa solicitar proteção contra otimizações de bateria
        val prefs = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_battery_check", 0)
        val now = System.currentTimeMillis()
        
        // Verificar apenas uma vez por dia
        if (now - lastCheck > 24 * 60 * 60 * 1000) {
            val isProtected = BatteryOptimizationHelper.checkAndRequestIfNeeded(this)
            
            if (!isProtected) {
                Toast.makeText(
                    this,
                    "Para garantir que o servidor funcione em background, por favor desative as otimizações de bateria para este app nas configurações.",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Salvar timestamp da verificação
            prefs.edit().putLong("last_battery_check", now).apply()
        }
    }
    
    private fun startServerService() {
        // Iniciar serviço em foreground para rodar em background
        val intent = Intent(this, PythonServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Iniciar heartbeat worker para ping a cada 15 minutos
        com.mritsoftware.mritserver.service.HeartbeatService.startHeartbeat(this)
        
        // Atualizar status após um pequeno delay para o servidor iniciar
        coroutineScope.launch {
            kotlinx.coroutines.delay(2000) // Dar mais tempo para Python inicializar
            updateServerStatus()
        }
    }
    
    private fun updateServerStatus() {
        coroutineScope.launch {
            try {
                // Verificar se o servidor está respondendo
                val url = java.net.URL("http://127.0.0.1:8000/health")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                
                val responseCode = connection.responseCode
                connection.disconnect()
                
                if (responseCode == 200) {
                    gatewayStatus.text = "Servidor rodando na porta 8000"
                    gatewayStatus.setTextColor(getColor(R.color.teal_700))
                } else {
                    gatewayStatus.text = "Servidor iniciando..."
                    gatewayStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                }
            } catch (e: Exception) {
                gatewayStatus.text = "Servidor iniciando..."
                gatewayStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Não parar o serviço aqui - deixar rodando em background
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_discover -> {
                val intent = Intent(this, DeviceDiscoveryActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.menu_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    
    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
    }
    
    private fun setupViews() {
        gatewayStatus = findViewById(R.id.gatewayStatus)
        deviceCount = findViewById(R.id.deviceCount)
        refreshButton = findViewById(R.id.refreshButton)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
    }
    
    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            devices,
            onDeviceClick = { device ->
                showDeviceDetails(device)
            }
        )
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter
    }
    
    private fun showDeviceDetails(device: TuyaDevice) {
        val intent = Intent(this, DeviceDetailsActivity::class.java)
        intent.putExtra("device", device)
        startActivity(intent)
    }
    
    private fun setupListeners() {
        refreshButton.setOnClickListener {
            refreshDevices()
        }
    }
    
    private fun refreshDevices() {
        Toast.makeText(this, "Escaneando dispositivos...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            try {
                // Inicializar Python se necessário
                if (!com.chaquo.python.Python.isStarted()) {
                    com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this@MainActivity))
                }
                
                val python = com.chaquo.python.Python.getInstance()
                val module = python.getModule("tuya_server")
                
                // Chamar scan_devices do Python
                val scanResult = withContext(Dispatchers.IO) {
                    try {
                        val result = module.callAttr("scan_devices")
                        if (result != null) {
                            // Converter PyObject para Map usando a API do Chaquopy
                            val devicesMap = mutableMapOf<String, Map<String, String>>()
                            
                            // Iterar sobre as chaves do dicionário Python
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
                            devicesMap
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Erro ao escanear dispositivos", e)
                        null
                    }
                }
                
                devices.clear()
                
                if (scanResult != null && scanResult.isNotEmpty()) {
                    val prefs = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
                    
                    for ((deviceId, deviceInfo) in scanResult) {
                        val ip = deviceInfo["ip"] ?: ""
                        val version = deviceInfo["version"]?.toString()
                        val savedName = prefs.getString("device_${deviceId}_name", null)
                        val name = savedName ?: "Dispositivo ${deviceId.take(8)}"
                        
                        devices.add(
                            TuyaDevice(
                                id = deviceId,
                                name = name,
                                type = TuyaDevice.DeviceType.OTHER,
                                isOnline = true,
                                isOn = false,
                                lanIp = ip,
                                protocolVersion = version
                            )
                        )
                    }
                    
                    Toast.makeText(this@MainActivity, "${devices.size} dispositivo(s) encontrado(s)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Nenhum dispositivo encontrado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao buscar dispositivos", e)
                Toast.makeText(this@MainActivity, "Erro ao escanear: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                updateUI()
            }
        }
    }
    
    private fun sendCommandToLocalServer(
        deviceId: String,
        localKey: String,
        action: String,
        lanIp: String
    ): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:8000/tuya/command")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val jsonBody = org.json.JSONObject().apply {
                put("action", action)
                put("tuya_device_id", deviceId)
                put("local_key", localKey)
                put("lan_ip", lanIp)
            }
            
            val outputStream = connection.outputStream
            val writer = java.io.OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            
            // Ler resposta para verificar se contém dados do dispositivo
            val response = if (responseCode == 200) {
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val responseText = reader.readText()
                    reader.close()
                    org.json.JSONObject(responseText)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            
            connection.disconnect()
            
            val success = responseCode == 200
            
            // Se comando foi bem-sucedido e resposta contém dados do dispositivo, atualizar cache
            if (success && response != null && response.optBoolean("ok", false) && response.has("device")) {
                try {
                    val deviceData = response.getJSONObject("device")
                    val deviceIdFromResponse = deviceData.optString("id", deviceId)
                    val ip = deviceData.optString("ip", lanIp).takeIf { it.isNotEmpty() }
                    val version = deviceData.optString("version", "").takeIf { it.isNotEmpty() }
                    val localKeyFromResponse = deviceData.optString("local_key", "").takeIf { it.isNotEmpty() } ?: localKey
                    
                    Log.d("MainActivity", "Dados recebidos do servidor - deviceId: $deviceIdFromResponse, ip: $ip, version: $version, localKey: ${if (localKeyFromResponse != null) "${localKeyFromResponse.take(8)}..." else "null"}")
                    
                    // Atualizar cache local - sempre atualizar mesmo se alguns campos estiverem vazios
                    val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(this)
                    deviceCacheManager.saveOrUpdateDevice(
                        deviceId = deviceIdFromResponse,
                        ip = ip,
                        version = version,
                        localKey = localKeyFromResponse
                    )
                    Log.d("MainActivity", "Cache atualizado após comando bem-sucedido para device $deviceIdFromResponse")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Erro ao atualizar cache após comando: ${e.message}", e)
                    e.printStackTrace()
                }
            } else {
                Log.w("MainActivity", "Resposta não contém dados do dispositivo ou comando falhou. success: $success, response: $response")
            }
            
            success
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getLocalKeyForDevice(deviceId: String): String? {
        // Buscar do dispositivo ou do SharedPreferences
        val device = devices.find { it.id == deviceId }
        return device?.localKey ?: getSharedPreferences("TuyaGateway", MODE_PRIVATE)
            .getString("device_${deviceId}_local_key", null)
    }
    
    private fun updateUI() {
        val onlineCount = devices.count { it.isOnline }
        gatewayStatus.text = if (onlineCount > 0) "Conectado" else "Desconectado"
        gatewayStatus.setTextColor(
            if (onlineCount > 0) getColor(R.color.teal_700)
            else getColor(android.R.color.holo_red_dark)
        )
        deviceCount.text = "${devices.size} dispositivos (${onlineCount} online)"
        deviceAdapter.notifyDataSetChanged()
    }
}

