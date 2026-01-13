# Problema: SSID não está sendo coletado no Android 10+

## 🔍 Por que o SSID não funciona?

### **Android 10+ (API 29+) - Restrições de Privacidade**

A partir do Android 10, o Google implementou restrições de privacidade que **bloqueiam o acesso ao SSID** mesmo com a permissão `ACCESS_WIFI_STATE`.

**O que acontece:**
- `WifiInfo.ssid` retorna `"<unknown ssid>"` ou `null`
- Isso é **intencional** do Android para proteger privacidade do usuário
- Apenas apps que o usuário conectou manualmente podem ver o SSID

---

## ✅ Soluções Possíveis

### **Opção 1: Adicionar Permissão de Localização (Parcial)**

No Android 10+, você precisa de **LOCATION** para obter SSID:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

**Limitações:**
- Requer permissão em tempo de execução (Runtime Permission)
- Usuário precisa autorizar manualmente
- Pode não funcionar em todos os dispositivos
- Alguns fabricantes (Samsung, Xiaomi) têm políticas ainda mais restritivas

### **Opção 2: Usar NetworkCapabilities (Android 10+)**

Tentar obter SSID via `NetworkCapabilities`:

```kotlin
val network = connectivityManager.activeNetwork
val capabilities = connectivityManager.getNetworkCapabilities(network)
val transportInfo = capabilities?.transportInfo as? WifiInfo
val ssid = transportInfo?.ssid
```

**Limitações:**
- Ainda pode retornar `null` ou `<unknown ssid>`
- Não funciona em todos os dispositivos

### **Opção 3: Aceitar Limitação (Recomendado)**

**O SSID não é crítico** para o funcionamento do sistema. O importante é:
- ✅ Velocidade WiFi (funciona)
- ✅ Nível de bateria (funciona)
- ✅ `servidor_online` (funciona)
- ⚠️ SSID (opcional, pode não funcionar)

---

## 🔧 Implementação Atual

O código atual **já tenta** obter o SSID, mas:
- Se retornar `null` ou `<unknown ssid>`, **não envia** no JSON
- O campo no banco fica `NULL` (o que é aceitável)
- **Não bloqueia** a atualização dos outros campos

---

## 📊 Status por Versão Android

| Versão Android | SSID Funciona? | Observação |
|----------------|----------------|------------|
| Android 6-9 (API 23-28) | ✅ **SIM** | Funciona normalmente |
| Android 10+ (API 29+) | ⚠️ **PARCIAL** | Pode retornar `<unknown ssid>` |
| Android 11+ (API 30+) | ❌ **NÃO** | Bloqueado por padrão |
| Android 12+ (API 31+) | ❌ **NÃO** | Bloqueado por padrão |

---

## 💡 Recomendação

**Manter como está** - O SSID é uma informação **opcional** e **não crítica**. O sistema funciona perfeitamente sem ele.

Se realmente precisar do SSID:
1. Adicionar permissão `ACCESS_FINE_LOCATION`
2. Solicitar permissão em tempo de execução
3. Aceitar que pode não funcionar em alguns dispositivos

---

## 🔍 Como Verificar se Está Funcionando

### **Logs do Android:**
```
[HeartbeatWorker] Erro ao obter SSID: ...
```

### **No Banco:**
```sql
SELECT wifi_ssid FROM tuya_devices;
-- Se retornar NULL, SSID não foi coletado
```

---

## ✅ Conclusão

**O SSID não está sendo coletado porque:**
- Android 10+ bloqueia por privacidade
- É uma limitação do sistema operacional
- **Não é crítico** para funcionamento

**O que funciona:**
- ✅ Velocidade WiFi
- ✅ Nível de bateria
- ✅ `servidor_online`

**Recomendação:** Aceitar que o SSID pode não estar disponível em dispositivos modernos.
