# Guia: Onde Encontrar AP Isolation em Diferentes Roteadores

## 🔍 Problema

Alguns roteadores não mostram a opção "AP Isolation" ou ela está em locais diferentes. Este guia ajuda a encontrar em vários modelos.

---

## 📋 Por Fabricante

### **TP-Link**

#### **Modelos Antigos:**
```
Wireless → Advanced → AP Isolation
```

#### **Modelos Novos (Interface Nova):**
```
Advanced → Wireless → Wireless Settings → AP Isolation
ou
Advanced → Network → AP Isolation
```

#### **Se não encontrar:**
- Procure por "Client Isolation" ou "Isolamento de Clientes"
- Alguns modelos têm em: **System Tools → Advanced Settings**

---

### **D-Link**

#### **Interface Padrão:**
```
Advanced → Wireless → AP Isolation
ou
Setup → Wireless Settings → Advanced → AP Isolation
```

#### **Modelos DIR:**
```
Wireless → Advanced → AP Isolation
```

#### **Se não encontrar:**
- Procure em: **Advanced → Network Filter → AP Isolation**
- Alguns modelos: **Management → Advanced → AP Isolation**

---

### **Netgear**

#### **Interface Padrão:**
```
Advanced → Wireless Settings → AP Isolation
ou
Advanced → Setup → Wireless Settings → AP Isolation
```

#### **Modelos Nighthawk:**
```
Advanced → Advanced Setup → Wireless Settings → AP Isolation
```

#### **Se não encontrar:**
- Procure por "Wireless Isolation" ou "Client Isolation"
- Alguns modelos: **Advanced → Wireless Settings → Enable AP Isolation**

---

### **Linksys**

#### **Interface Padrão:**
```
Wireless → Advanced Wireless Settings → AP Isolation
ou
Wireless → Wireless Security → Advanced → AP Isolation
```

#### **Modelos Velop (Mesh):**
```
Wi-Fi Settings → Advanced → AP Isolation
```

#### **Se não encontrar:**
- Procure em: **Connectivity → Advanced → AP Isolation**
- Alguns modelos: **Administration → Advanced → AP Isolation**

---

### **Intelbras**

#### **Interface Padrão:**
```
Wireless → Advanced → AP Isolation
ou
Rede → Wireless → Configurações Avançadas → Isolamento de Clientes
```

#### **Se não encontrar:**
- Procure por "Isolamento de Clientes" ou "Client Isolation"
- Alguns modelos: **Avançado → Wireless → AP Isolation**

---

### **Mercusys**

#### **Interface Padrão:**
```
Advanced → Wireless → AP Isolation
ou
Wireless → Advanced → AP Isolation
```

---

### **Multilaser**

#### **Interface Padrão:**
```
Wireless → Advanced → AP Isolation
ou
Rede → Wireless → Avançado → Isolamento de Clientes
```

---

### **Asus**

#### **Interface Padrão:**
```
Wireless → Professional → AP Isolation
ou
Advanced → Wireless → Professional → AP Isolation
```

#### **Modelos Router:**
```
Wireless → General → AP Isolation
```

---

### **Huawei**

#### **Interface Padrão:**
```
Advanced → Wireless → AP Isolation
ou
More Functions → Wireless Settings → AP Isolation
```

---

## 🔍 Se Não Encontrar a Opção

### **Métodos Alternativos:**

#### **1. Buscar no Manual do Roteador**
- Procure no manual por "AP Isolation", "Client Isolation" ou "Isolamento"
- Alguns roteadores chamam de nomes diferentes

#### **2. Buscar na Interface Web**
- Use Ctrl+F (ou Cmd+F no Mac) na página do roteador
- Busque por: "isolation", "isolamento", "client", "cliente"

#### **3. Verificar Versão do Firmware**
- Alguns roteadores só têm essa opção em versões mais novas
- Atualize o firmware se possível

#### **4. Contatar Suporte do Fabricante**
- Se não encontrar, pode não ter a opção
- Nesse caso, use polling reverso (já implementado)

---

## ✅ Solução Alternativa: Polling Reverso

**Se não conseguir desativar AP Isolation**, o sistema já tem polling reverso implementado:

### **Como Funciona:**
1. Cliente faz requisição GET para `/tuya/poll?tuya_device_id=...`
2. Servidor retorna comandos pendentes (se houver)
3. Cliente processa comandos
4. Repete a cada 5-10 segundos

### **Vantagens:**
- ✅ Funciona mesmo com AP Isolation ativado
- ✅ Funciona mesmo com firewall bloqueando
- ✅ Cliente inicia conexão (não é bloqueada)

### **Desvantagens:**
- ⚠️ Latência (até 5-10 segundos)
- ⚠️ Consome mais bateria (polling constante)

---

## 🧪 Como Testar se AP Isolation Está Ativo

### **Teste 1: Ping**
```bash
# Do outro dispositivo:
ping [IP_DO_SERVIDOR]

# Se não pingar → AP Isolation ativado
# Se pingar → Não é AP Isolation (pode ser firewall)
```

### **Teste 2: Port Scan**
```bash
# Do outro dispositivo:
nmap -p 8000 [IP_DO_SERVIDOR]

# Se porta estiver fechada → Firewall ou AP Isolation
# Se porta estiver aberta → Deve funcionar
```

---

## 💡 Recomendação

**Para as 25 unidades:**

1. **Primeiro**: Tentar encontrar AP Isolation (usar este guia)
2. **Segundo**: Se não encontrar, usar polling reverso (já implementado)
3. **Terceiro**: Se polling não for suficiente, considerar mudança de roteador

---

## 📞 Nomes Alternativos da Opção

A opção pode aparecer com nomes diferentes:
- AP Isolation
- Client Isolation
- Isolamento de Clientes
- Wireless Isolation
- Station Isolation
- AP Client Isolation
- Isolamento AP
- Client-to-Client Blocking

**Busque por qualquer um desses termos na interface do roteador.**
