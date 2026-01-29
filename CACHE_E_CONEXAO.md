# Sistema de Cache e Tratamento de Problemas de Conexão

## 📦 Como Funciona o Cache

### 1. **O que é salvo no cache?**
- Lista completa de dispositivos Tuya (ID, IP, versão, nome, local_key)
- Nome do site
- Timestamp da última atualização

### 2. **Quando o cache é salvo?**
✅ **Automaticamente quando:**
- O servidor retorna dados com sucesso (HTTP 200)
- A sincronização é bem-sucedida
- O scan de dispositivos encontra dispositivos

### 3. **Onde fica armazenado?**
- **SharedPreferences** do Android (persistente)
- Permanece mesmo após fechar o app
- Validade: 7 dias (mas pode ser usado mesmo expirado)

---

## 🔄 Fluxo de Funcionamento

### **Cenário 1: Tudo Funcionando Normalmente**

```
1. App tenta buscar dispositivos do servidor
   ↓
2. Servidor responde com sucesso (HTTP 200)
   ↓
3. Dados são salvos automaticamente no cache
   ↓
4. App exibe dispositivos na tela
```

### **Cenário 2: Servidor Offline ou Erro de Conexão**

```
1. App tenta buscar dispositivos do servidor
   ↓
2. ❌ Erro: Servidor não responde / Timeout / Erro de rede
   ↓
3. Sistema verifica se existe cache disponível
   ↓
4. ✅ Se existe cache → Carrega dados do cache
   ↓
5. App exibe dispositivos do cache (mesmo que antigos)
```

### **Cenário 3: Servidor Responde mas JSON está com Problema**

```
1. App tenta buscar dispositivos do servidor
   ↓
2. ⚠️ Servidor responde HTTP 200, mas JSON está vazio/null
   ↓
3. Sistema detecta que dados são inválidos
   ↓
4. ✅ Carrega dados do cache como fallback
   ↓
5. App exibe dispositivos do cache
```

---

## 🛡️ Proteções Implementadas

### **1. Múltiplas Camadas de Fallback**

#### **Camada 1: Servidor Python (Flask)**
- Tenta buscar via servidor local na porta 8000
- Timeout: 30 segundos
- Retries automáticos no servidor Python (3 tentativas)

#### **Camada 2: HTTP Direto**
- Se Python falhar, tenta HTTP direto para `/tuya/scan`
- Timeout: 30 segundos

#### **Camada 3: Cache Local**
- Se todas as tentativas falharem, carrega do cache
- Funciona mesmo offline

### **2. Tratamento de Erros Específicos**

```kotlin
// Erros tratados:
- ConnectException → "Servidor não está respondendo"
- SocketTimeoutException → "Timeout - servidor ocupado"
- IOException → "Erro de comunicação"
- JSON inválido → Carrega do cache
- HTTP != 200 → Carrega do cache
```

### **3. Envio de Comandos com Fallback**

Quando você clica para ligar/desligar um dispositivo:

```
1. Tenta enviar via servidor Python (Flask)
   - Timeout: 10s conexão, 30s leitura
   - Retries no servidor: 3 tentativas com backoff exponencial
   ↓
2. ❌ Se servidor falhar:
   ↓
3. ✅ Fallback: Envia diretamente via TuyaProtocol (Kotlin)
   - Usa os dados do cache (IP, versão, local_key)
   - Funciona mesmo se servidor estiver offline
```

---

## 🔧 Detalhes Técnicos

### **Cache Manager (`DeviceCacheManager.kt`)**

#### **Métodos Principais:**

```kotlin
// Salvar dados
saveDevicesFromMap(devicesMap)  // Salva quando recebe do servidor
saveDevices(devices)            // Salva lista de DeviceInfo

// Carregar dados
loadDevicesAsMap()              // Retorna Map<String, Map<String, String>>
loadDevices()                   // Retorna List<DeviceInfo>

// Verificações
hasCache()                      // Verifica se existe cache
isCacheEnabled()                 // Verifica se cache está habilitado
getLastUpdateTimestamp()        // Quando foi atualizado pela última vez
```

#### **Estrutura dos Dados no Cache:**

```json
{
  "devices_cache": "[
    {
      \"id\": \"bf1234567890abcdef\",
      \"ip\": \"192.168.1.100\",
      \"version\": \"3.3\",
      \"name\": \"Interruptor Sala\",
      \"local_key\": \"abc123def456\"
    }
  ]",
  "last_update_timestamp": 1704067200000,
  "cached_site_name": "Hospital Central"
}
```

### **Fluxo de Sincronização (`syncWithServer`)**

```kotlin
1. Faz POST para http://127.0.0.1:8000/tuya/sync
2. Se responseCode == 200:
   ✅ Considera sucesso (mesmo se houver timeout ao ler resposta)
   ✅ Salva dados no cache
3. Se responseCode != 200:
   ❌ Tenta carregar do cache
4. Se erro de conexão:
   ❌ Carrega do cache
```

### **Envio de Comandos (`FlaskService.sendCommand`)**

```kotlin
1. Tenta enviar via servidor Python:
   POST /tuya/command
   {
     "action": "on",
     "tuya_device_id": "...",
     "local_key": "...",
     "lan_ip": "auto",
     "version": 3.3
   }
   
2. Se servidor falhar (timeout, erro, etc):
   ✅ Fallback: TuyaProtocol.sendCommand() diretamente
   - Usa IP do cache
   - Usa versão do cache
   - Funciona offline
```

---

## 📊 Exemplos Práticos

### **Exemplo 1: App Abre sem Internet**

```
1. Usuário abre o app
2. App tenta conectar ao servidor → ❌ Falha (sem internet)
3. Sistema verifica cache → ✅ Existe cache de 2 dias atrás
4. App carrega dispositivos do cache
5. Usuário vê lista de dispositivos (mesmo que antiga)
6. Usuário pode ligar/desligar usando cache (IP, versão salvos)
```

### **Exemplo 2: Servidor Lento mas Funciona**

```
1. Usuário clica em "Sincronizar"
2. Servidor responde HTTP 200, mas demora para enviar JSON
3. Timeout ao ler resposta → ⚠️ Mas código é 200
4. Sistema considera sucesso ✅
5. Navega para tela de conectado
6. Se JSON chegou, salva no cache
7. Se não chegou, usa cache anterior
```

### **Exemplo 3: Servidor Offline mas Dispositivo na Rede Local**

```
1. Usuário quer ligar um dispositivo
2. Servidor Python está offline → ❌
3. Fallback ativado: Envia diretamente via TuyaProtocol
4. Usa IP do cache: 192.168.1.100
5. Usa versão do cache: 3.3
6. Comando enviado com sucesso ✅
7. Dispositivo liga mesmo sem servidor
```

---

## ⚙️ Configurações

### **Habilitar/Desabilitar Cache**

```kotlin
deviceCacheManager.setCacheEnabled(true)   // Habilitar
deviceCacheManager.setCacheEnabled(false)  // Desabilitar
```

### **Limpar Cache Manualmente**

```kotlin
deviceCacheManager.clearCache()  // Remove todos os dados do cache
```

### **Verificar Status do Cache**

```kotlin
if (deviceCacheManager.hasCache()) {
    val timestamp = deviceCacheManager.getLastUpdateTimestamp()
    val ageHours = (System.currentTimeMillis() - timestamp) / (60 * 60 * 1000)
    println("Cache tem $ageHours horas")
}
```

---

## 🎯 Resumo

✅ **Cache é salvo automaticamente** quando servidor funciona  
✅ **Cache é carregado automaticamente** quando servidor falha  
✅ **Comandos funcionam offline** usando dados do cache  
✅ **Múltiplas camadas de fallback** garantem resiliência  
✅ **Validade de 7 dias**, mas pode ser usado mesmo expirado  
✅ **Persistente** - funciona mesmo após fechar o app  

O sistema foi projetado para **sempre funcionar**, mesmo com problemas de rede ou servidor offline! 🚀
