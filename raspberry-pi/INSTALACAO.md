# MRIT Server — Manual de Instalação no Raspberry Pi

## O que você vai precisar

- Raspberry Pi 3 ou 4
- Cartão MicroSD (16 GB ou mais, Classe 10)
- Cabo de alimentação USB-C
- Acesso à internet (apenas durante a instalação)

---

## Passo 1 — Gravar o sistema operacional

1. Baixe o **Raspberry Pi Imager**: https://www.raspberrypi.com/software/
2. Clique em **Escolher dispositivo** → selecione seu modelo de Pi
3. Clique em **Escolher SO** → `Raspberry Pi OS (other)` → `Raspberry Pi OS Lite (64-bit)`
4. Clique em **Escolher armazenamento** → selecione o cartão SD
5. Clique no ícone de engrenagem (**Configurações avançadas**) e preencha:

| Campo | Valor |
|-------|-------|
| Nome do host | `mrit-pi` |
| Usuário | `mrit` |
| Senha | (escolha uma senha, anote) |
| WiFi (opcional) | SSID e senha da rede local |
| Fuso horário | America/Sao_Paulo |
| Habilitar SSH | ✅ Sim |

6. Clique em **Salvar** → **Gravar**

---

## Passo 2 — Ligar e conectar via SSH

Insira o cartão no Pi, conecte a alimentação e aguarde ~60 segundos.

```bash
ssh mrit@mrit-pi.local
```

> Se não funcionar com `.local`, descubra o IP no roteador e use `ssh mrit@192.168.x.x`

---

## Passo 3 — Clonar o repositório

```bash
git clone https://github.com/MRITSoftware/mrit-server.git
cd mrit-server
```

---

## Passo 4 — Executar o instalador

```bash
bash raspberry-pi/scripts/install.sh
```

O instalador vai:
- Criar o ambiente Python
- Instalar as dependências
- **Perguntar o e-mail da unidade** (ex: `itaquera@gelafit.com.br`) — use o e-mail cadastrado no sistema
- Configurar os serviços para iniciar automaticamente
- Iniciar o servidor

---

## Passo 5 — Acessar o painel web

Abra no navegador (qualquer dispositivo na mesma rede):

```
http://mrit-pi.local
```

**Senha:** `MRITSERVER#REDEGELAFIT`

---

## Passo 6 — Configurar o WiFi (se necessário)

Se o Pi não estiver conectado a uma rede WiFi:

1. Conecte ao hotspot **MRIT-Setup** (senha: `mrit1234`)
2. Acesse `http://mrit-pi.local`
3. Vá em **WiFi** → **Buscar Redes** → conecte à rede desejada

---

## Passo 7 — Sincronizar o dispositivo Tuya

1. No painel web, vá em **Dispositivos**
2. Clique em **Buscar Dispositivos Tuya** (aguarde ~20 segundos)
3. Clique em **Sincronizar com Banco** no dispositivo encontrado

---

## Passo 8 — Verificar no Windows

No seu computador, dentro da pasta do projeto:

```powershell
.\check-servers.ps1
```

A nova unidade deve aparecer como **ONLINE** com versão `1.0-PI`.

---

## Restaurar a partir de um backup (troca de Pi)

Se o Pi anterior ainda funciona:
1. Acesse `http://mrit-pi.local` → **Configuração** → **Exportar config.json**
2. Salve o arquivo

No Pi novo (após o Passo 4):
1. Acesse `http://mrit-pi.local`
2. **Configuração** → **Importar config.json** → selecione o arquivo exportado
3. O servidor reinicia com todas as configurações da unidade anterior
4. Vá em **Dispositivos** → **Buscar** → **Sincronizar** para atualizar o IP da placa

---

## Resumo dos serviços instalados

| Serviço | Função | Porta |
|---------|--------|-------|
| `mrit-server` | Controla dispositivos Tuya, heartbeat, comandos remotos | 8000 |
| `mrit-webui` | Painel web de configuração | 80 |
| `mrit-wifi-check.timer` | Verifica WiFi a cada 5 min, cria hotspot se necessário | — |

---

## Comandos úteis via SSH

```bash
# Ver logs do servidor em tempo real
journalctl -u mrit-server -f

# Ver logs do painel web
journalctl -u mrit-webui -f

# Reiniciar serviços manualmente
sudo systemctl restart mrit-server mrit-webui

# Status de tudo
sudo systemctl status mrit-server mrit-webui mrit-wifi-check.timer
```

---

## Comandos úteis no Windows (pasta do projeto)

```powershell
# Ver status de todas as unidades
.\check-servers.ps1

# Enviar atualização OTA para uma unidade
.\deploy-ota.ps1 -Site "email@gelafit.com.br"

# Enviar atualização OTA para todas as unidades
.\deploy-ota.ps1
```

---

## Informações do sistema

| Item | Valor |
|------|-------|
| Usuário do Pi | `mrit` |
| Senha do painel web | `MRITSERVER#REDEGELAFIT` |
| Hotspot de configuração | `MRIT-Setup` / senha `mrit1234` |
| Endereço local | `http://mrit-pi.local` |
| Repositório | https://github.com/MRITSoftware/mrit-server |
