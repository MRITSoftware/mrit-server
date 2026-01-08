package com.mritsoftware.mritserver.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Gerencia cache persistente de dispositivos e dados do servidor.
 * Salva automaticamente quando recebe dados do JSON e carrega quando o servidor falha.
 */
class DeviceCacheManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("DeviceCache", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "DeviceCacheManager"
        private const val KEY_DEVICES_CACHE = "devices_cache"
        private const val KEY_LAST_UPDATE = "last_update_timestamp"
        private const val KEY_SITE_NAME = "cached_site_name"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        
        // Tempo máximo de cache válido (7 dias em milissegundos)
        private const val MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
    
    /**
     * Salva lista de dispositivos recebidos do JSON
     */
    fun saveDevices(devices: List<DeviceInfo>) {
        try {
            if (devices.isEmpty()) {
                Log.w(TAG, "Tentando salvar cache vazio - ignorando")
                return
            }
            
            val devicesArray = JSONArray()
            for (device in devices) {
                val deviceObj = JSONObject().apply {
                    put("id", device.id)
                    put("ip", device.ip ?: "")
                    put("version", device.version ?: "")
                    put("name", device.name ?: "")
                    put("local_key", device.localKey ?: "")
                }
                devicesArray.put(deviceObj)
            }
            
            val cacheJson = devicesArray.toString()
            val timestamp = System.currentTimeMillis()
            
            val success = sharedPreferences.edit()
                .putString(KEY_DEVICES_CACHE, cacheJson)
                .putLong(KEY_LAST_UPDATE, timestamp)
                .commit()  // Usar commit() em vez de apply() para garantir persistência
            
            if (success) {
                Log.d(TAG, "Cache salvo com sucesso: ${devices.size} dispositivos (timestamp: $timestamp)")
            } else {
                Log.e(TAG, "Falha ao salvar cache - commit() retornou false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar cache de dispositivos", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Salva lista de dispositivos a partir de um Map (formato usado pelo ConnectedActivity)
     */
    fun saveDevicesFromMap(devicesMap: Map<String, Map<String, String>>) {
        try {
            val devices = devicesMap.map { (deviceId, deviceInfo) ->
                DeviceInfo(
                    id = deviceId,
                    ip = deviceInfo["ip"],
                    version = deviceInfo["version"],
                    name = deviceInfo["name"],
                    localKey = deviceInfo["local_key"]
                )
            }
            saveDevices(devices)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar cache de dispositivos do Map", e)
        }
    }
    
    /**
     * Salva lista de dispositivos a partir de um JSONArray
     */
    fun saveDevicesFromJsonArray(devicesArray: JSONArray) {
        try {
            val devices = mutableListOf<DeviceInfo>()
            for (i in 0 until devicesArray.length()) {
                val deviceObj = devicesArray.getJSONObject(i)
                devices.add(
                    DeviceInfo(
                        id = deviceObj.getString("id"),
                        ip = deviceObj.optString("ip", ""),
                        version = deviceObj.optString("version", ""),
                        name = deviceObj.optString("name", ""),
                        localKey = deviceObj.optString("local_key", "")
                    )
                )
            }
            saveDevices(devices)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar cache de dispositivos do JSONArray", e)
        }
    }
    
    /**
     * Carrega dispositivos do cache
     * @return Lista de dispositivos ou null se cache inválido/expirado
     */
    fun loadDevices(): List<DeviceInfo>? {
        return try {
            val cacheJson = sharedPreferences.getString(KEY_DEVICES_CACHE, null)
            if (cacheJson.isNullOrEmpty()) {
                Log.d(TAG, "Cache vazio")
                return null
            }
            
            // Verificar se cache está expirado
            val lastUpdate = sharedPreferences.getLong(KEY_LAST_UPDATE, 0)
            val cacheAge = System.currentTimeMillis() - lastUpdate
            if (cacheAge > MAX_CACHE_AGE_MS) {
                Log.w(TAG, "Cache expirado (${cacheAge / (24 * 60 * 60 * 1000)} dias)")
                // Não limpar, apenas avisar - pode ser útil mesmo expirado
            }
            
            val devicesArray = JSONArray(cacheJson)
            val devices = mutableListOf<DeviceInfo>()
            
            for (i in 0 until devicesArray.length()) {
                val deviceObj = devicesArray.getJSONObject(i)
                devices.add(
                    DeviceInfo(
                        id = deviceObj.getString("id"),
                        ip = deviceObj.optString("ip", "").takeIf { it.isNotEmpty() },
                        version = deviceObj.optString("version", "").takeIf { it.isNotEmpty() },
                        name = deviceObj.optString("name", "").takeIf { it.isNotEmpty() },
                        localKey = deviceObj.optString("local_key", "").takeIf { it.isNotEmpty() }
                    )
                )
            }
            
            Log.d(TAG, "Cache carregado: ${devices.size} dispositivos (idade: ${cacheAge / (60 * 60 * 1000)} horas)")
            devices
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar cache de dispositivos", e)
            null
        }
    }
    
    /**
     * Carrega dispositivos do cache e retorna como Map (formato usado pelo ConnectedActivity)
     */
    fun loadDevicesAsMap(): Map<String, Map<String, String>>? {
        val devices = loadDevices() ?: return null
        
        return devices.associate { device ->
            device.id to mapOf(
                "id" to device.id,
                "ip" to (device.ip ?: ""),
                "version" to (device.version ?: ""),
                "name" to (device.name ?: ""),
                "local_key" to (device.localKey ?: "")
            )
        }
    }
    
    /**
     * Salva nome do site
     */
    fun saveSiteName(siteName: String) {
        sharedPreferences.edit()
            .putString(KEY_SITE_NAME, siteName)
            .apply()
        Log.d(TAG, "Nome do site salvo: $siteName")
    }
    
    /**
     * Carrega nome do site do cache
     */
    fun loadSiteName(): String? {
        return sharedPreferences.getString(KEY_SITE_NAME, null)
    }
    
    /**
     * Verifica se cache está habilitado
     */
    fun isCacheEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_CACHE_ENABLED, true) // Habilitado por padrão
    }
    
    /**
     * Habilita ou desabilita cache
     */
    fun setCacheEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_CACHE_ENABLED, enabled)
            .apply()
        Log.d(TAG, "Cache ${if (enabled) "habilitado" else "desabilitado"}")
    }
    
    /**
     * Limpa todo o cache
     */
    fun clearCache() {
        sharedPreferences.edit()
            .remove(KEY_DEVICES_CACHE)
            .remove(KEY_LAST_UPDATE)
            .remove(KEY_SITE_NAME)
            .apply()
        Log.d(TAG, "Cache limpo")
    }
    
    /**
     * Obtém timestamp da última atualização do cache
     */
    fun getLastUpdateTimestamp(): Long {
        return sharedPreferences.getLong(KEY_LAST_UPDATE, 0)
    }
    
    /**
     * Salva ou atualiza um dispositivo individual no cache
     */
    fun saveOrUpdateDevice(deviceId: String, ip: String?, version: String?, name: String? = null, localKey: String? = null) {
        try {
            val currentDevices = loadDevices()?.toMutableList() ?: mutableListOf()
            
            // Verificar se dispositivo já existe
            val existingIndex = currentDevices.indexOfFirst { it.id == deviceId }
            
            val deviceInfo = DeviceInfo(
                id = deviceId,
                ip = ip,
                version = version,
                name = name,
                localKey = localKey
            )
            
            if (existingIndex >= 0) {
                // Atualizar dispositivo existente (mesclar dados)
                val existing = currentDevices[existingIndex]
                currentDevices[existingIndex] = DeviceInfo(
                    id = deviceId,
                    ip = ip ?: existing.ip,
                    version = version ?: existing.version,
                    name = name ?: existing.name,
                    localKey = localKey ?: existing.localKey
                )
                Log.d(TAG, "Dispositivo atualizado no cache: $deviceId")
            } else {
                // Adicionar novo dispositivo
                currentDevices.add(deviceInfo)
                Log.d(TAG, "Dispositivo adicionado ao cache: $deviceId")
            }
            
            saveDevices(currentDevices)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar/atualizar dispositivo no cache: ${e.message}", e)
        }
    }
    
    /**
     * Compara dois Maps de dispositivos e retorna true se forem diferentes
     */
    fun areDevicesDifferent(newDevices: Map<String, Map<String, String>>): Boolean {
        return try {
            val cachedDevices = loadDevicesAsMap() ?: return true // Se não há cache, considera diferente
            
            // Comparar quantidade
            if (cachedDevices.size != newDevices.size) {
                Log.d(TAG, "Dispositivos diferentes: quantidade diferente (cache: ${cachedDevices.size}, novo: ${newDevices.size})")
                return true
            }
            
            // Comparar cada dispositivo
            for ((deviceId, newDeviceInfo) in newDevices) {
                val cachedDeviceInfo = cachedDevices[deviceId]
                
                if (cachedDeviceInfo == null) {
                    Log.d(TAG, "Dispositivo diferente: novo dispositivo $deviceId não está no cache")
                    return true
                }
                
                // Comparar campos importantes
                val fieldsToCompare = listOf("id", "ip", "version", "name", "local_key")
                for (field in fieldsToCompare) {
                    val cachedValue = cachedDeviceInfo[field] ?: ""
                    val newValue = newDeviceInfo[field] ?: ""
                    
                    if (cachedValue != newValue) {
                        Log.d(TAG, "Dispositivo diferente: $deviceId campo $field mudou (cache: $cachedValue, novo: $newValue)")
                        return true
                    }
                }
            }
            
            Log.d(TAG, "Dispositivos são iguais, não precisa atualizar cache")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao comparar dispositivos: ${e.message}", e)
            true // Em caso de erro, considera diferente para garantir atualização
        }
    }
    
    /**
     * Verifica se cache existe e não está vazio
     */
    fun hasCache(): Boolean {
        return try {
            val cacheJson = sharedPreferences.getString(KEY_DEVICES_CACHE, null)
            val hasCache = !cacheJson.isNullOrEmpty()
            if (hasCache) {
                val lastUpdate = getLastUpdateTimestamp()
                Log.d(TAG, "Cache verificado: existe=${hasCache}, timestamp=${lastUpdate}")
            } else {
                Log.d(TAG, "Cache verificado: não existe")
            }
            hasCache
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar cache: ${e.message}", e)
            false
        }
    }
    
    /**
     * Classe de dados para informações do dispositivo
     */
    data class DeviceInfo(
        val id: String,
        val ip: String? = null,
        val version: String? = null,
        val name: String? = null,
        val localKey: String? = null
    )
}
