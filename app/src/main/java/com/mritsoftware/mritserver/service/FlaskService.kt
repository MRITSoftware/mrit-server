package com.mritsoftware.mritserver.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mritsoftware.mritserver.model.TuyaDevice
import com.mritsoftware.mritserver.service.DeviceCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FlaskService(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("TuyaGateway", Context.MODE_PRIVATE)
    
    companion object {
        private const val DEFAULT_SERVER_URL = "http://192.168.1.100:8000"
    }
    
    /**
     * Obtém a URL do servidor Flask configurada
     */
    fun getServerUrl(): String {
        return sharedPreferences.getString("flask_server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }
    
    /**
     * Define a URL do servidor Flask
     */
    fun setServerUrl(url: String) {
        sharedPreferences.edit().putString("flask_server_url", url).apply()
    }
    
    /**
     * Verifica se o servidor Flask está online
     */
    suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getServerUrl()}/health")
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
    
    /**
     * Envia comando para o servidor Flask controlar um dispositivo Tuya
     */
    suspend fun sendCommand(
        deviceId: String,
        localKey: String,
        action: String, // "on" ou "off"
        lanIp: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getServerUrl()}/tuya/command")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val jsonBody = JSONObject().apply {
                put("action", action)
                put("tuya_device_id", deviceId)
                put("local_key", localKey)
                if (lanIp != null) {
                    put("lan_ip", lanIp)
                } else {
                    put("lan_ip", "auto")
                }
            }
            
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            
            val response = if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                JSONObject(response)
            } else {
                val reader = BufferedReader(InputStreamReader(connection.errorStream))
                val errorResponse = reader.readText()
                reader.close()
                JSONObject(errorResponse)
            }
            
            connection.disconnect()
            
            val success = responseCode == 200 && response.optBoolean("ok", false)
            
            // Se comando foi bem-sucedido e resposta contém dados do dispositivo, atualizar cache
            if (success && response.has("device")) {
                try {
                    val deviceData = response.getJSONObject("device")
                    val deviceIdFromResponse = deviceData.optString("id", deviceId)
                    val ip = deviceData.optString("ip", "").takeIf { it.isNotEmpty() }
                    val version = deviceData.optString("version", "").takeIf { it.isNotEmpty() }
                    val localKeyFromResponse = deviceData.optString("local_key", "").takeIf { it.isNotEmpty() } ?: localKey
                    
                    android.util.Log.d("FlaskService", "Dados recebidos do servidor - deviceId: $deviceIdFromResponse, ip: $ip, version: $version, localKey: ${if (localKeyFromResponse != null) "${localKeyFromResponse.take(8)}..." else "null"}")
                    
                    // Atualizar cache local - sempre atualizar mesmo se alguns campos estiverem vazios
                    val deviceCacheManager = DeviceCacheManager(context)
                    deviceCacheManager.saveOrUpdateDevice(
                        deviceId = deviceIdFromResponse,
                        ip = ip,
                        version = version,
                        localKey = localKeyFromResponse
                    )
                    android.util.Log.d("FlaskService", "Cache atualizado após comando bem-sucedido para device $deviceIdFromResponse")
                } catch (e: Exception) {
                    android.util.Log.e("FlaskService", "Erro ao atualizar cache após comando: ${e.message}", e)
                    e.printStackTrace()
                }
            } else {
                android.util.Log.w("FlaskService", "Resposta não contém dados do dispositivo ou comando falhou. success: $success, hasDevice: ${response.has("device")}")
            }
            
            return@withContext success
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * Obtém informações do site do servidor
     */
    suspend fun getSiteInfo(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getServerUrl()}/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                json.optString("site", null)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}


