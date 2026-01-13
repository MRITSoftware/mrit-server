package com.mritsoftware.mritserver.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.mritsoftware.mritserver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var localIpText: TextInputEditText
    private lateinit var siteNameText: TextInputEditText
    private lateinit var testButton: MaterialButton
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        setupToolbar()
        setupViews()
        setupListeners()
        loadInfo()
    }
    
    private fun setupToolbar() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configurações"
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    private fun setupViews() {
        localIpText = findViewById(R.id.localIpText)
        siteNameText = findViewById(R.id.siteNameText)
        testButton = findViewById(R.id.testButton)
    }
    
    private fun loadInfo() {
        // Carregar IP local
        val localIp = getLocalIpAddress()
        val ipText = if (localIp != null) {
            "$localIp:8000\n\n💡 Para acessar de outros dispositivos na mesma rede:\nhttp://$localIp:8000/tuya/command"
        } else {
            "Não disponível\n\n⚠️ Verifique se WiFi está conectado"
        }
        localIpText.setText(ipText)
        
        // Carregar nome da unidade
        val prefs = getSharedPreferences("TuyaGateway", MODE_PRIVATE)
        val siteName = prefs.getString("site_name", "ANDROID_DEVICE") ?: "ANDROID_DEVICE"
        siteNameText.setText(siteName)
    }
    
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("169.254")) { // Ignorar link-local
                            return ip
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Erro ao obter IP local", e)
            null
        }
    }
    
    private fun setupListeners() {
        testButton.setOnClickListener {
            testConnection()
        }
    }
    
    private fun testConnection() {
        testButton.isEnabled = false
        testButton.text = "Testando..."
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = java.net.URL("http://127.0.0.1:8000/health")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                
                val responseCode = connection.responseCode
                val response = if (responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val responseText = reader.readText()
                    reader.close()
                    org.json.JSONObject(responseText)
                } else {
                    null
                }
                
                connection.disconnect()
                
                CoroutineScope(Dispatchers.Main).launch {
                    testButton.isEnabled = true
                    testButton.text = "Testar Conexão"
                    
                    if (responseCode == 200 && response != null) {
                        val site = response.optString("site", "N/A")
                        Toast.makeText(
                            this@SettingsActivity,
                            "✅ Servidor OK! Site: $site",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "❌ Servidor não está respondendo. Aguarde alguns segundos.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    testButton.isEnabled = true
                    testButton.text = "Testar Conexão"
                    Toast.makeText(
                        this@SettingsActivity,
                        "❌ Erro: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

