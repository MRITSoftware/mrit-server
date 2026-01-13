# Campos Adicionais no Heartbeat

## ✅ Implementado

O sistema agora coleta e envia as seguintes informações junto com o heartbeat:

1. **SSID (Nome da Rede WiFi)**
2. **Velocidade do Link WiFi** (em Mbps)
3. **Nível de Bateria** (0-100%)

---

## 📋 O que Precisa ser Feito no Banco de Dados

### **SIM, você precisa adicionar os campos na tabela `tuya_devices`**

Execute os seguintes comandos SQL no Supabase:

```sql
-- Adicionar campo para nome da rede WiFi (SSID)
ALTER TABLE tuya_devices 
ADD COLUMN IF NOT EXISTS wifi_ssid TEXT;

-- Adicionar campo para velocidade do link WiFi (em Mbps)
ALTER TABLE tuya_devices 
ADD COLUMN IF NOT EXISTS wifi_speed INTEGER;

-- Adicionar campo para nível de bateria (0-100)
ALTER TABLE tuya_devices 
ADD COLUMN IF NOT EXISTS battery_level INTEGER;

-- Adicionar comentários para documentação
COMMENT ON COLUMN tuya_devices.wifi_ssid IS 'Nome da rede WiFi (SSID) onde o servidor está conectado';
COMMENT ON COLUMN tuya_devices.wifi_speed IS 'Velocidade do link WiFi em Mbps';
COMMENT ON COLUMN tuya_devices.battery_level IS 'Nível de bateria do dispositivo Android (0-100%)';
```

---

## 🔍 Como Funciona

### **1. Coleta de Informações (Android)**

O `HeartbeatWorker` coleta automaticamente:

#### **SSID (Nome da Rede):**
```kotlin
val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
val wifiInfo = wifiManager.connectionInfo
val ssid = wifiInfo.ssid.replace("\"", "")
```

#### **Velocidade WiFi:**
```kotlin
val linkSpeed = wifiInfo.linkSpeed // Em Mbps
```

#### **Nível de Bateria:**
```kotlin
val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
```

### **2. Envio para Servidor**

As informações são enviadas no JSON do heartbeat:
```json
{
  "tuya_device_id": "abc123",
  "wifi_ssid": "MinhaRede_WiFi",
  "wifi_speed": 65,
  "battery_level": 85
}
```

### **3. Atualização no Banco**

O servidor Python atualiza os campos junto com `servidor_online`:
```python
update_data = {
    "servidor_online": timestamp_iso,
    "wifi_ssid": wifi_ssid,      # Se fornecido
    "wifi_speed": wifi_speed,    # Se fornecido
    "battery_level": battery_level  # Se fornecido
}
```

---

## ⚠️ Limitações e Considerações

### **1. SSID (Nome da Rede)**

**Limitações:**
- Android 10+ (API 29+) pode retornar `<unknown ssid>` por questões de privacidade
- Requer permissão `ACCESS_WIFI_STATE` (já temos)
- Pode não funcionar em alguns dispositivos por políticas de segurança

**O que fazer se não funcionar:**
- Campo ficará `NULL` no banco
- Sistema continua funcionando normalmente
- Não é crítico para funcionamento

### **2. Velocidade WiFi**

**O que representa:**
- Velocidade do **link** entre dispositivo e roteador (não velocidade de internet)
- Valores típicos: 54, 65, 72, 150, 300, 450, 600, 867 Mbps
- Depende do padrão WiFi (802.11n, 802.11ac, etc.)

**Limitações:**
- Pode não estar disponível em alguns Android
- Pode variar dependendo da distância do roteador
- Não reflete velocidade real de internet

### **3. Nível de Bateria**

**O que representa:**
- Porcentagem de bateria restante (0-100%)
- Atualizado a cada heartbeat (15 minutos)

**Limitações:**
- Pode não estar disponível em tablets conectados (sempre 100%)
- Em alguns dispositivos pode não funcionar
- Não é crítico se não funcionar

---

## 📊 Exemplo de Dados no Banco

Após adicionar os campos, a tabela `tuya_devices` terá:

| tuya_device_id | servidor_online | wifi_ssid | wifi_speed | battery_level |
|----------------|-----------------|-----------|------------|---------------|
| abc123 | 2024-01-15T10:30:00+00:00 | MinhaRede_WiFi | 65 | 85 |
| def456 | 2024-01-15T10:30:00+00:00 | OutraRede | 150 | 92 |
| ghi789 | 2024-01-15T10:30:00+00:00 | NULL | NULL | 78 |

---

## ✅ Checklist de Implementação

- [x] **Coleta de informações no Android** ✅ Implementado
- [x] **Envio para servidor Python** ✅ Implementado
- [x] **Atualização no banco** ✅ Implementado
- [ ] **Adicionar campos no Supabase** ⚠️ **PRECISA FAZER**

---

## 🔧 Como Adicionar Campos no Supabase

### **Opção 1: Via SQL Editor**

1. Acesse o Supabase Dashboard
2. Vá em **SQL Editor**
3. Execute o SQL acima

### **Opção 2: Via Table Editor**

1. Acesse o Supabase Dashboard
2. Vá em **Table Editor** → `tuya_devices`
3. Clique em **Add Column**
4. Adicione cada campo:
   - `wifi_ssid`: Type `text`, Nullable
   - `wifi_speed`: Type `int4`, Nullable
   - `battery_level`: Type `int4`, Nullable

---

## 🧪 Como Testar

### **1. Verificar se informações estão sendo coletadas:**

Logs do Android devem mostrar:
```
[HeartbeatWorker] SSID: MinhaRede_WiFi
[HeartbeatWorker] Velocidade WiFi: 65 Mbps
[HeartbeatWorker] Bateria: 85%
```

### **2. Verificar se estão sendo enviadas:**

Logs do servidor Python devem mostrar:
```
[HEARTBEAT] Atualizando servidor_online para device abc123 (timestamp: ..., SSID: MinhaRede_WiFi, Velocidade: 65 Mbps, Bateria: 85%)
```

### **3. Verificar no banco:**

Após heartbeat executar, verificar no Supabase:
```sql
SELECT tuya_device_id, servidor_online, wifi_ssid, wifi_speed, battery_level
FROM tuya_devices
WHERE tuya_device_id = 'seu_device_id';
```

---

## ⚠️ Importante

### **Se não adicionar os campos no banco:**
- ❌ Atualização vai falhar (erro 400/500 do Supabase)
- ❌ Campos não serão salvos
- ⚠️ Mas `servidor_online` ainda será atualizado (se campos opcionais falharem)

### **Solução:**
- ✅ Adicionar campos no banco **ANTES** de usar
- ✅ Ou remover campos opcionais do código se não quiser usar

---

## 💡 Resumo

**É possível?** ✅ **SIM**

**Precisa adicionar campos?** ✅ **SIM** (wifi_ssid, wifi_speed, battery_level)

**Funciona automaticamente?** ✅ **SIM** (após adicionar campos no banco)

**O que acontece se não adicionar campos?** ⚠️ Atualização pode falhar, mas sistema tenta continuar
