package com.mritsoftware.mritserver.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WelcomeActivity : AppCompatActivity() {
    
    private lateinit var sharedPreferences: SharedPreferences
    
    // Views
    private lateinit var searchingText: TextView
    private lateinit var searchingSubtext: TextView
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var startServerButton: MaterialButton
    private lateinit var retrySearchButton: MaterialButton
    private lateinit var retrySearchButton2: MaterialButton
    private lateinit var siteNameInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var selectedDeviceInfo: TextView
    private lateinit var backToDevicesButton: MaterialButton
    
    private lateinit var deviceAdapter: WelcomeDeviceAdapter
    private val devices = mutableListOf<WelcomeDevice>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var isServerStarted = false
    private var selectedDevice: WelcomeDevice? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPreferences = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
        
        // Verificar se já configurou o nome do site
        if (isSiteConfigured()) {
            startConnectedActivity()
            return
        }
        
        setContentView(R.layout.activity_welcome)
        
        setupViews()
        setupRecyclerView()
        setupListeners()
        
        // Iniciar servidor Python em background
        startPythonServer()
        
        // Aguardar servidor iniciar e então buscar dispositivos
        coroutineScope.launch {
            delay(3000) // Dar tempo para o servidor Python iniciar
            searchDevices()
        }
    }
    
    private fun setupViews() {
        searchingText = findViewById(R.id.searchingText)
        searchingSubtext = findViewById(R.id.searchingSubtext)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        startServerButton = findViewById(R.id.startServerButton)
        retrySearchButton = findViewById(R.id.retrySearchButton)
        retrySearchButton2 = findViewById(R.id.retrySearchButton2)
        siteNameInput = findViewById(R.id.siteNameInput)
        selectedDeviceInfo = findViewById(R.id.selectedDeviceInfo)
        backToDevicesButton = findViewById(R.id.backToDevicesButton)
    }
    
    private fun setupRecyclerView() {
        // Criar adapter com uma lista vazia inicial - ele terá sua própria cópia
        deviceAdapter = WelcomeDeviceAdapter(mutableListOf())
        deviceAdapter.setOnDeviceClickListener { device ->
            selectedDevice = device
            showDeviceNameInput()
        }
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter
        // Desabilitar nested scrolling para funcionar dentro do NestedScrollView
        devicesRecyclerView.isNestedScrollingEnabled = false
        Log.d("WelcomeActivity", "RecyclerView configurado, adapter inicializado")
    }
    
    private fun setupListeners() {
        startServerButton.setOnClickListener {
            syncWithSupabase()
        }
        
        retrySearchButton.setOnClickListener {
            searchDevices()
        }
        
        retrySearchButton2.setOnClickListener {
            searchDevices()
        }
        
        backToDevicesButton.setOnClickListener {
            showDevicesFound()
        }
    }
    
    private fun startPythonServer() {
        val intent = Intent(this, PythonServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServerStarted = true
    }
    
    private fun searchDevices() {
        // Mostrar tela de busca
        findViewById<View>(R.id.searchingCard).visibility = View.VISIBLE
        findViewById<View>(R.id.devicesFoundCard).visibility = View.GONE
        findViewById<View>(R.id.noDevicesCard).visibility = View.GONE
        
        searchingText.text = "Buscando dispositivos..."
        searchingSubtext.text = "Escaneando a rede local"
        
        coroutineScope.launch {
            try {
                // Aguardar um pouco mais para garantir que o servidor está pronto
                delay(2000)
                
                // Tentar usar o endpoint HTTP primeiro
                val scanResult = withContext(Dispatchers.IO) {
                    try {
                        // Verificar se o servidor HTTP está respondendo
                        val healthUrl = URL("http://127.0.0.1:8000/health")
                        val healthConnection = healthUrl.openConnection() as HttpURLConnection
                        healthConnection.requestMethod = "GET"
                        healthConnection.connectTimeout = 3000
                        healthConnection.readTimeout = 3000
                        
                        val healthCode = healthConnection.responseCode
                        healthConnection.disconnect()
                        
                        if (healthCode == 200) {
                            // Servidor HTTP está rodando, usar endpoint
                            Log.d("WelcomeActivity", "Usando endpoint HTTP para buscar dispositivos")
                            val devicesUrl = URL("http://127.0.0.1:8000/tuya/devices")
                            val devicesConnection = devicesUrl.openConnection() as HttpURLConnection
                            devicesConnection.requestMethod = "GET"
                            devicesConnection.connectTimeout = 40000
                            devicesConnection.readTimeout = 40000
                            
                            val devicesCode = devicesConnection.responseCode
                            if (devicesCode == 200) {
                                val reader = BufferedReader(InputStreamReader(devicesConnection.inputStream))
                                val response = reader.readText()
                                reader.close()
                                devicesConnection.disconnect()
                                
                                // Validar se response não está vazio
                                if (response.isBlank()) {
                                    Log.w("WelcomeActivity", "Resposta vazia do servidor")
                                    return@withContext null
                                }
                                
                                val json = JSONObject(response)
                                
                                // Usar optBoolean para evitar exceção se "ok" não existir
                                if (json.optBoolean("ok", false)) {
                                    // Usar optJSONArray para evitar exceção se "devices" não existir ou for null
                                    val devicesArray = json.optJSONArray("devices")
                                    
                                    if (devicesArray == null) {
                                        Log.w("WelcomeActivity", "Campo 'devices' não encontrado ou é null no JSON")
                                        return@withContext null
                                    }
                                    
                                    val devicesMap = mutableMapOf<String, Map<String, String>>()
                                    
                                    for (i in 0 until devicesArray.length()) {
                                        try {
                                            val deviceObj = devicesArray.optJSONObject(i)
                                            if (deviceObj != null) {
                                                val deviceId = deviceObj.optString("id", "")
                                                if (deviceId.isNotEmpty()) {
                                                    val deviceMap = mutableMapOf<String, String>()
                                                    deviceMap["ip"] = deviceObj.optString("ip", "")
                                                    deviceMap["version"] = deviceObj.optString("version", "")
                                                    devicesMap[deviceId] = deviceMap
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("WelcomeActivity", "Erro ao processar dispositivo $i: ${e.message}")
                                        }
                                    }
                                    
                                    // Só retornar se houver dispositivos válidos
                                    if (devicesMap.isNotEmpty()) {
                                        Log.d("WelcomeActivity", "Encontrados ${devicesMap.size} dispositivos via HTTP")
                                        devicesMap
                                    } else {
                                        Log.w("WelcomeActivity", "Nenhum dispositivo válido encontrado no JSON")
                                        null
                                    }
                                } else {
                                    Log.w("WelcomeActivity", "JSON retornou ok=false")
                                    null
                                }
                            } else {
                                devicesConnection.disconnect()
                                null
                            }
                        } else {
                            // Servidor HTTP não está rodando, usar Python direto
                            Log.d("WelcomeActivity", "Servidor HTTP não está pronto, usando Python direto")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("WelcomeActivity", "Erro ao tentar usar endpoint HTTP", e)
                        null
                    }
                } ?: run {
                    // Fallback: usar Python direto
                    Log.d("WelcomeActivity", "Usando Python direto para buscar dispositivos")
                    withContext(Dispatchers.IO) {
                        try {
                            // Inicializar Python se necessário
                            if (!com.chaquo.python.Python.isStarted()) {
                                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this@WelcomeActivity))
                            }
                            
                            val python = com.chaquo.python.Python.getInstance()
                            val module = python.getModule("tuya_server")
                            
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
                                
                                Log.d("WelcomeActivity", "Encontrados ${devicesMap.size} dispositivos via Python")
                                devicesMap
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.e("WelcomeActivity", "Erro ao escanear dispositivos via Python", e)
                            null
                        }
                    }
                }
                
                // Criar uma nova lista para evitar problemas de referência
                val newDevicesList = mutableListOf<WelcomeDevice>()
                
                if (scanResult != null && scanResult.isNotEmpty()) {
                    // Salvar dispositivos no cache
                    try {
                        val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(this@WelcomeActivity)
                        deviceCacheManager.saveDevicesFromMap(scanResult)
                        Log.d("WelcomeActivity", "Cache salvo com ${scanResult.size} dispositivos")
                    } catch (e: Exception) {
                        Log.e("WelcomeActivity", "Erro ao salvar cache: ${e.message}", e)
                    }
                    
                    for ((deviceId, deviceInfo) in scanResult) {
                        val ip = deviceInfo["ip"] ?: ""
                        val version = deviceInfo["version"]?.toString()
                        
                        newDevicesList.add(
                            WelcomeDevice(
                                id = deviceId,
                                ip = ip,
                                protocolVersion = version
                            )
                        )
                    }
                    
                    // Atualizar UI na thread principal
                    withContext(Dispatchers.Main) {
                        Log.d("WelcomeActivity", "Preparando para exibir ${newDevicesList.size} dispositivos")
                        Log.d("WelcomeActivity", "Dispositivos: ${newDevicesList.map { "${it.id} - ${it.ip}" }}")
                        
                        // Atualizar a lista devices ANTES de atualizar o adapter
                        devices.clear()
                        devices.addAll(newDevicesList)
                        
                        // Toast para debug - mostrar quantos dispositivos foram encontrados
                        Toast.makeText(
                            this@WelcomeActivity,
                            "Encontrados ${devices.size} dispositivo(s)",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Garantir que o adapter está configurado no RecyclerView
                        if (devicesRecyclerView.adapter == null) {
                            devicesRecyclerView.adapter = deviceAdapter
                        }
                        
                        // Atualizar adapter com uma CÓPIA da lista para evitar problemas de referência
                        deviceAdapter.updateDevices(devices.toList())
                        
                        // Mostrar tela de dispositivos encontrados
                        showDevicesFound()
                        
                        // Verificar se o adapter tem itens
                        Log.d("WelcomeActivity", "Adapter itemCount: ${deviceAdapter.itemCount}")
                        Log.d("WelcomeActivity", "RecyclerView visibility: ${devicesRecyclerView.visibility}")
                        Log.d("WelcomeActivity", "RecyclerView adapter: ${devicesRecyclerView.adapter != null}")
                        
                        // Forçar layout após um pequeno delay para garantir que a view está visível
                        devicesRecyclerView.postDelayed({
                            devicesRecyclerView.requestLayout()
                            devicesRecyclerView.invalidate()
                        }, 200)
                    }
                } else {
                    // Nenhum dispositivo encontrado
                    withContext(Dispatchers.Main) {
                        showNoDevices()
                        Log.d("WelcomeActivity", "Nenhum dispositivo encontrado")
                        Toast.makeText(
                            this@WelcomeActivity,
                            "Nenhum dispositivo encontrado na rede",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("WelcomeActivity", "Erro ao buscar dispositivos", e)
                withContext(Dispatchers.Main) {
                    showNoDevices()
                    Toast.makeText(this@WelcomeActivity, "Erro ao buscar dispositivos: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showDevicesFound() {
        findViewById<View>(R.id.searchingCard).visibility = View.GONE
        findViewById<View>(R.id.devicesFoundCard).visibility = View.VISIBLE
        findViewById<View>(R.id.noDevicesCard).visibility = View.GONE
        findViewById<View>(R.id.deviceNameInputCard).visibility = View.GONE
        
        // Garantir que o RecyclerView está visível e configurado
        devicesRecyclerView.visibility = View.VISIBLE
        if (devicesRecyclerView.adapter == null) {
            devicesRecyclerView.adapter = deviceAdapter
        }
        
        // Forçar atualização do adapter e layout
        deviceAdapter.notifyDataSetChanged()
        devicesRecyclerView.requestLayout()
        devicesRecyclerView.invalidate()
        
        Log.d("WelcomeActivity", "showDevicesFound() chamado - RecyclerView visível, adapter tem ${deviceAdapter.itemCount} itens")
    }
    
    private fun showDeviceNameInput() {
        selectedDevice?.let { device ->
            val deviceIdSuffix = if (device.id.length >= 5) {
                device.id.takeLast(5)
            } else {
                device.id
            }
            selectedDeviceInfo.text = "Dispositivo selecionado: $deviceIdSuffix\nIP: ${device.ip}"
        }
        
        findViewById<View>(R.id.searchingCard).visibility = View.GONE
        findViewById<View>(R.id.devicesFoundCard).visibility = View.GONE
        findViewById<View>(R.id.noDevicesCard).visibility = View.GONE
        findViewById<View>(R.id.deviceNameInputCard).visibility = View.VISIBLE
    }
    
    private fun showNoDevices() {
        findViewById<View>(R.id.searchingCard).visibility = View.GONE
        findViewById<View>(R.id.devicesFoundCard).visibility = View.GONE
        findViewById<View>(R.id.noDevicesCard).visibility = View.VISIBLE
        findViewById<View>(R.id.deviceNameInputCard).visibility = View.GONE
    }
    
    private fun syncWithSupabase() {
        // Validar nome da unidade
        val siteName = siteNameInput.text?.toString()?.trim() ?: ""
        if (siteName.isBlank()) {
            Toast.makeText(this, "Por favor, informe o nome da unidade", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Mostrar loading
        startServerButton.isEnabled = false
        startServerButton.text = "Sincronizando..."
        
        coroutineScope.launch {
            try {
                // Atualizar nome do site no servidor Python
                withContext(Dispatchers.IO) {
                    try {
                        if (!com.chaquo.python.Python.isStarted()) {
                            com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this@WelcomeActivity))
                        }
                        val python = com.chaquo.python.Python.getInstance()
                        val module = python.getModule("tuya_server")
                        module.callAttr("update_site_name", siteName)
                    } catch (e: Exception) {
                        Log.e("WelcomeActivity", "Erro ao atualizar site name", e)
                    }
                }
                
                // Preparar dados para sincronização apenas com o dispositivo selecionado
                val selectedDeviceId = selectedDevice?.id
                if (selectedDeviceId == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WelcomeActivity, "Nenhum dispositivo selecionado", Toast.LENGTH_SHORT).show()
                        startServerButton.isEnabled = true
                        startServerButton.text = "Ligar Servidor"
                    }
                    return@launch
                }
                
                val devicesData = JSONObject().apply {
                    put(selectedDeviceId, JSONObject().apply {
                        put("name", siteName) // Nome da unidade como name do device
                    })
                }
                
                val syncBody = JSONObject().apply {
                    put("site_id", siteName) // site_id é o nome da unidade
                    put("devices", devicesData)
                }
                
                // Chamar endpoint de sincronização
                val syncResult = withContext(Dispatchers.IO) {
                    syncWithServer(syncBody.toString())
                }
                
                if (syncResult) {
                    // Salvar que a configuração foi concluída
                    sharedPreferences.edit()
                        .putBoolean("welcome_completed", true)
                        .putString("site_name", siteName)
                        .putString("device_id", selectedDeviceId) // Salvar device_id para heartbeat
                        .apply()
                    
                    // Iniciar heartbeat após sincronização bem-sucedida
                    com.mritsoftware.mritserver.service.HeartbeatService.startHeartbeat(this@WelcomeActivity)
                    
                    // Ir para tela de conectado
                    startConnectedActivity()
                } else {
                    Toast.makeText(this@WelcomeActivity, "Erro ao sincronizar com o servidor", Toast.LENGTH_LONG).show()
                    startServerButton.isEnabled = true
                    startServerButton.text = "Ligar Servidor"
                }
                
            } catch (e: Exception) {
                Log.e("WelcomeActivity", "Erro na sincronização", e)
                Toast.makeText(this@WelcomeActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                startServerButton.isEnabled = true
                startServerButton.text = "Ligar Servidor"
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
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(body)
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                connection.disconnect()
                return@withContext json.getBoolean("ok")
            }
            
            connection.disconnect()
            false
        } catch (e: Exception) {
            Log.e("WelcomeActivity", "Erro ao sincronizar", e)
            false
        }
    }
    
    private fun isSiteConfigured(): Boolean {
        return sharedPreferences.getBoolean("welcome_completed", false) &&
               sharedPreferences.getString("site_name", null) != null
    }
    
    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun startConnectedActivity() {
        val intent = Intent(this, ConnectedActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Não parar o serviço aqui - deixar rodando em background
    }
}
