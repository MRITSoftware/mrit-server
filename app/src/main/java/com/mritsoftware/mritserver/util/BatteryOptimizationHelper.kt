package com.mritsoftware.mritserver.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper para proteger o app contra otimizações de bateria do Android
 * que podem matar processos em background.
 */
object BatteryOptimizationHelper {
    
    private const val TAG = "BatteryOptimizationHelper"
    
    /**
     * Verifica se o app está sendo otimizado (pode ser morto pelo sistema).
     * Retorna true se está sendo otimizado (precisa de ação do usuário).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            // Android 5.x e anteriores não têm essa restrição
            true
        }
    }
    
    /**
     * Solicita ao usuário que ignore otimizações de bateria para este app.
     * Abre a tela de configurações do Android.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    Log.d(TAG, "Solicitando ignorar otimizações de bateria...")
                    
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    try {
                        context.startActivity(intent)
                        Log.d(TAG, "Tela de configurações de bateria aberta")
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao abrir tela de configurações: ${e.message}")
                        // Fallback: abrir configurações gerais de bateria
                        openBatterySettings(context)
                    }
                } else {
                    Log.d(TAG, "App já está ignorando otimizações de bateria")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar otimizações de bateria: ${e.message}", e)
                // Fallback: abrir configurações gerais de bateria
                openBatterySettings(context)
            }
        } else {
            Log.d(TAG, "Android ${Build.VERSION.SDK_INT} não requer verificação de otimizações de bateria")
        }
    }
    
    /**
     * Abre as configurações de bateria do dispositivo (fallback).
     */
    private fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Configurações gerais de bateria abertas")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir configurações de bateria: ${e.message}", e)
        }
    }
    
    /**
     * Verifica e solicita automaticamente ignorar otimizações se necessário.
     * Retorna true se já está ignorando ou se a solicitação foi feita.
     */
    fun checkAndRequestIfNeeded(context: Context): Boolean {
        return if (isIgnoringBatteryOptimizations(context)) {
            Log.d(TAG, "App já está protegido contra otimizações de bateria")
            true
        } else {
            Log.w(TAG, "App NÃO está protegido - solicitando permissão do usuário")
            requestIgnoreBatteryOptimizations(context)
            false
        }
    }
}
