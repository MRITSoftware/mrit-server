package com.mritsoftware.mritserver.worker

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Worker que atualiza o campo servidor_online no banco de dados a cada 15 minutos
 * para indicar que o servidor está online e funcionando.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val PREFS_NAME = "TuyaGateway"
        private const val KEY_SAVED_DEVICE_ID = "heartbeat_device_id" // Chave específica para heartbeat
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            Log.d(TAG, "=== HEARTBEAT WORKER EXECUTANDO ===")
            Log.d(TAG, "App pode estar fechado - WorkManager executando em background")
            Log.d(TAG, "Iniciando heartbeat...")
            
            // Verificar se o servidor está rodando
            val serverRunning = checkServerHealth()
            if (!serverRunning) {
                val runAttemptCount = runAttemptCount
                Log.w(TAG, "Servidor não está respondendo (tentativa $runAttemptCount), aguardando e tentando novamente...")
                
                // Aguardar um pouco antes de retry (backoff exponencial)
                val delaySeconds = minOf(30L, (1L shl minOf(runAttemptCount, 5)).toLong()) // Max 30 segundos
                delay(delaySeconds * 1000)
                
                // Verificar novamente antes de retry
                val serverRunningRetry = checkServerHealth()
                if (!serverRunningRetry) {
                    Log.w(TAG, "Servidor ainda não está respondendo após aguardar, retry...")
                    return androidx.work.ListenableWorker.Result.retry() // Tentar novamente mais tarde
                } else {
                    Log.d(TAG, "Servidor voltou a responder após aguardar, continuando...")
                }
            }
            
            val sharedPreferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // PRIORIDADE 1: Usar device_id salvo do último heartbeat bem-sucedido (fallback)
            var deviceId = sharedPreferences.getString(KEY_SAVED_DEVICE_ID, null)
            if (!deviceId.isNullOrEmpty()) {
                Log.d(TAG, "Device ID obtido do app state (fallback): $deviceId")
            }
            
            // PRIORIDADE 2: Tentar buscar do SharedPreferences geral
            if (deviceId.isNullOrEmpty()) {
                deviceId = sharedPreferences.getString("device_id", null)
                if (!deviceId.isNullOrEmpty()) {
                    Log.d(TAG, "Device ID obtido do SharedPreferences: $deviceId")
                }
            }
            
            // PRIORIDADE 3: Tentar buscar do cache
            if (deviceId.isNullOrEmpty()) {
                val deviceCacheManager = com.mritsoftware.mritserver.service.DeviceCacheManager(applicationContext)
                val cachedDevices = deviceCacheManager.loadDevicesAsMap()
                
                if (cachedDevices != null && cachedDevices.isNotEmpty()) {
                    // Pegar o primeiro dispositivo do cache
                    deviceId = cachedDevices.keys.firstOrNull()
                    if (!deviceId.isNullOrEmpty()) {
                        Log.d(TAG, "Device ID obtido do cache: $deviceId")
                    }
                }
            }
            
            if (deviceId.isNullOrEmpty()) {
                Log.w(TAG, "Device ID não encontrado em nenhuma fonte, pulando heartbeat")
                return androidx.work.ListenableWorker.Result.success() // Não é erro, apenas não há device configurado
            }
            
            // Chamar endpoint de heartbeat com retry automático
            var success = false
            var attempts = 0
            val maxAttempts = 3
            
            while (!success && attempts < maxAttempts) {
                attempts++
                Log.d(TAG, "Tentando enviar heartbeat (tentativa $attempts/$maxAttempts)...")
                
                success = sendHeartbeat(deviceId)
                
                if (!success && attempts < maxAttempts) {
                    Log.w(TAG, "Falha ao enviar heartbeat, aguardando 5 segundos antes de tentar novamente...")
                    delay(5000) // Aguardar 5 segundos entre tentativas
                }
            }
            
            if (success) {
                // Salvar device_id no app state para usar como fallback no próximo heartbeat
                sharedPreferences.edit()
                    .putString(KEY_SAVED_DEVICE_ID, deviceId)
                    .putLong("last_heartbeat_time", System.currentTimeMillis()) // Salvar timestamp
                    .apply()
                Log.d(TAG, "✅ Heartbeat enviado com sucesso para device $deviceId após $attempts tentativa(s)")
                androidx.work.ListenableWorker.Result.success()
            } else {
                Log.e(TAG, "❌ Falha ao enviar heartbeat após $maxAttempts tentativas, retry agendado")
                // Não limpar o device_id salvo, para usar como fallback na próxima tentativa
                androidx.work.ListenableWorker.Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar heartbeat: ${e.message}", e)
            androidx.work.ListenableWorker.Result.retry()
        }
    }
    
    private suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Verificar conectividade antes de testar servidor
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasInternet = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            if (!hasWifi && !hasInternet) {
                Log.w(TAG, "WiFi não está conectado, pulando heartbeat")
                return@withContext false
            }
            
            val url = URL("http://127.0.0.1:8000/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000 // Aumentar timeout para redes mais lentas
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Servidor está respondendo (WiFi: $hasWifi)")
                true
            } else {
                Log.w(TAG, "Servidor retornou código $responseCode (WiFi: $hasWifi)")
                false
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Timeout ao verificar servidor (rede pode estar lenta): ${e.message}")
            false
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Erro de conexão (servidor offline ou rede bloqueada): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar servidor: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }
    
    private suspend fun sendHeartbeat(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("http://127.0.0.1:8000/tuya/heartbeat")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            // Coletar informações do dispositivo
            val deviceInfo = collectDeviceInfo()
            
            val jsonBody = JSONObject().apply {
                put("tuya_device_id", deviceId)
                // Adicionar informações do dispositivo se disponíveis
                deviceInfo["wifi_ssid"]?.let { put("wifi_ssid", it) }
                deviceInfo["wifi_speed"]?.let { put("wifi_speed", it) }
                deviceInfo["battery_level"]?.let { put("battery_level", it) }
            }
            
            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                try {
                    val json = JSONObject(response)
                    val ok = json.optBoolean("ok", false)
                    if (ok) {
                        val message = json.optString("message", "")
                        Log.d(TAG, "Heartbeat recebido com sucesso: $message")
                        connection.disconnect()
                        return@withContext true
                    } else {
                        val error = json.optString("error", "Erro desconhecido")
                        Log.w(TAG, "Servidor retornou ok=false: $error")
                        connection.disconnect()
                        return@withContext false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao parsear resposta JSON: ${e.message}")
                    // Se a resposta não for JSON válido, mas o código foi 200, considerar sucesso
                    Log.d(TAG, "Resposta não-JSON recebida, mas código 200 - considerando sucesso")
                    connection.disconnect()
                    return@withContext true
                }
            } else {
                Log.w(TAG, "Erro HTTP ao enviar heartbeat: $responseCode")
                connection.disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar heartbeat: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Coleta informações do dispositivo: SSID, velocidade WiFi e nível de bateria
     */
    private fun collectDeviceInfo(): Map<String, Any?> {
        val info = mutableMapOf<String, Any?>()
        
        try {
            // Obter informações de WiFi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = connectivityManager?.activeNetwork
                val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                
                // Verificar se está conectado via WiFi
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    // Obter SSID (nome da rede)
                    try {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Android 10+ requer permissão especial, mas podemos tentar
                            val wifiInfo = wifiManager?.connectionInfo
                            val ssid = wifiInfo?.ssid?.replace("\"", "") // Remover aspas
                            if (ssid != null && ssid != "<unknown ssid>") {
                                info["wifi_ssid"] = ssid
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val wifiInfo = wifiManager?.connectionInfo
                            val ssid = wifiInfo?.ssid?.replace("\"", "")
                            if (ssid != null && ssid != "<unknown ssid>") {
                                info["wifi_ssid"] = ssid
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao obter SSID: ${e.message}")
                    }
                    
                    // Obter velocidade do link (Link Speed)
                    try {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val wifiInfo = wifiManager?.connectionInfo
                        val linkSpeed = wifiInfo?.linkSpeed // Em Mbps
                        if (linkSpeed != null && linkSpeed > 0) {
                            info["wifi_speed"] = linkSpeed
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao obter velocidade WiFi: ${e.message}")
                    }
                }
            }
            
            // Obter nível de bateria
            try {
                val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (batteryLevel != null && batteryLevel >= 0) {
                        info["battery_level"] = batteryLevel
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val intent = android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
                    val batteryLevel = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    if (batteryLevel >= 0 && scale > 0) {
                        val level = (batteryLevel * 100 / scale.toFloat()).toInt()
                        info["battery_level"] = level
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao obter nível de bateria: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao coletar informações do dispositivo: ${e.message}", e)
        }
        
        return info
    }
}
