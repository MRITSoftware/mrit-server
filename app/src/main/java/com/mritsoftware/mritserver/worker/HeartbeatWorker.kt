package com.mritsoftware.mritserver.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Worker que atualiza o campo updated_at no banco de dados a cada 10 minutos
 * para verificar se o servidor está respondendo.
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Iniciando heartbeat...")
            
            // Verificar se o servidor está rodando
            val serverRunning = checkServerHealth()
            if (!serverRunning) {
                Log.w(TAG, "Servidor não está respondendo, pulando heartbeat")
                return@withContext Result.retry() // Tentar novamente mais tarde
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
                return@withContext Result.success() // Não é erro, apenas não há device configurado
            }
            
            // Chamar endpoint de heartbeat
            val success = sendHeartbeat(deviceId)
            
            if (success) {
                // Salvar device_id no app state para usar como fallback no próximo heartbeat
                sharedPreferences.edit()
                    .putString(KEY_SAVED_DEVICE_ID, deviceId)
                    .apply()
                Log.d(TAG, "Heartbeat enviado com sucesso para device $deviceId (salvo no app state)")
                Result.success()
            } else {
                Log.w(TAG, "Falha ao enviar heartbeat, tentando novamente mais tarde")
                // Não limpar o device_id salvo, para usar como fallback na próxima tentativa
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar heartbeat: ${e.message}", e)
            Result.retry()
        }
    }
    
    private suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
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
    
    private suspend fun sendHeartbeat(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("http://127.0.0.1:8000/tuya/heartbeat")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val jsonBody = JSONObject().apply {
                put("tuya_device_id", deviceId)
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
            false
        }
    }
}
