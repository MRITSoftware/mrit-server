# check-servers.ps1 — Status de todas as unidades MRIT e ultimos comandos admin
# Uso:
#   .\check-servers.ps1                                   # todas as unidades
#   .\check-servers.ps1 -Site "itaquera@gelafit.com.br"  # unidade especifica

param(
    [string]$Site = ""
)

$SUPABASE_URL = "https://kihyhoqbrkwbfudttevo.supabase.co"
$SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtpaHlob3Ficmt3YmZ1ZHR0ZXZvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTU1NTUwMjcsImV4cCI6MjAzMTEzMTAyN30.XtBTlSiqhsuUIKmhAMEyxofV-dRst7240n912m4O4Us"

$headers = @{
    "apikey"        = $SUPABASE_KEY
    "Authorization" = "Bearer $SUPABASE_KEY"
}

$now = [DateTime]::UtcNow

# -- Servidores --------------------------------------------------------------
$devsUri = "$SUPABASE_URL/rest/v1/tuya_devices?select=site_id,versao,servidor_online,wifi_ssid,wifi_speed"
if ($Site) { $devsUri += "&site_id=eq.$Site" }
$devsUri += "&order=servidor_online.desc.nullslast"

try {
    $devs = Invoke-RestMethod -Uri $devsUri -Headers $headers
} catch {
    Write-Host "Erro ao conectar ao Supabase: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host "  MRIT — Status dos Servidores" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host ""

if (-not $devs) {
    Write-Host "  Nenhuma unidade encontrada." -ForegroundColor Yellow
} else {
    foreach ($d in $devs) {
        $siteLabel = if ($d.site_id) { $d.site_id } else { "(sem site_id)" }
        $versao    = if ($d.versao)  { $d.versao }  else { "-" }
        $ssid      = if ($d.wifi_ssid)   { $d.wifi_ssid }   else { "-" }
        $speed     = if ($d.wifi_speed)  { "$($d.wifi_speed) Mbps" } else { "-" }

        if ($d.servidor_online) {
            $last    = [DateTime]::Parse($d.servidor_online).ToUniversalTime()
            $diffMin = [int](($now - $last).TotalMinutes)
            if ($diffMin -lt 20) {
                $statusText  = "ONLINE"
                $statusColor = "Green"
                $timeStr     = "ha $diffMin min"
            } else {
                $statusText  = "OFFLINE"
                $statusColor = "Red"
                if ($diffMin -lt 1440) { $timeStr = "ha $diffMin min" }
                else { $timeStr = "ha $([int]($diffMin/1440))d" }
            }
        } else {
            $statusText  = "NUNCA"
            $statusColor = "DarkGray"
            $timeStr     = ""
        }

        Write-Host "  $siteLabel" -ForegroundColor White
        Write-Host "    Status : " -NoNewline
        Write-Host "$statusText  $timeStr" -ForegroundColor $statusColor
        Write-Host "    Versao : $versao"
        Write-Host "    WiFi   : $ssid  $speed"
        Write-Host ""
    }
}

# -- Ultimos comandos admin --------------------------------------------------
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host "  Ultimos Comandos Admin" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host ""

$cmdsUri = "$SUPABASE_URL/rest/v1/servidor_admin_commands?order=created_at.desc&limit=10"
if ($Site) {
    $cmdsUri = "$SUPABASE_URL/rest/v1/servidor_admin_commands?site_name=eq.$Site&order=created_at.desc&limit=5"
}

try {
    $cmds = Invoke-RestMethod -Uri $cmdsUri -Headers $headers
} catch {
    Write-Host "  Erro ao buscar comandos." -ForegroundColor Red
    exit 0
}

if (-not $cmds) {
    Write-Host "  Nenhum comando enviado ainda." -ForegroundColor DarkGray
} else {
    foreach ($c in $cmds) {
        $statusColor = switch ($c.status) {
            "concluido"  { "Green" }
            "erro"       { "Red" }
            "executando" { "Yellow" }
            default      { "DarkGray" }
        }
        $ts = if ($c.created_at.Length -ge 16) { $c.created_at.Substring(0,16) } else { $c.created_at }
        Write-Host "  [$ts] " -NoNewline -ForegroundColor DarkGray
        Write-Host "$($c.site_name)" -NoNewline -ForegroundColor White
        Write-Host " — $($c.comando) " -NoNewline
        Write-Host $c.status -ForegroundColor $statusColor

        if ($c.resultado) {
            $preview = $c.resultado -replace "`n"," "
            if ($preview.Length -gt 110) { $preview = $preview.Substring(0,110) + "..." }
            Write-Host "    $preview" -ForegroundColor DarkGray
        }
    }
}

Write-Host ""
