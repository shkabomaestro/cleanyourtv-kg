# Dijagnoza Wi-Fi-ja na sticku: zasto puca mreza. Ne menja nista na sticku, samo cita.
#   powershell -ExecutionPolicy Bypass -File work\mreza.ps1 -TvHost <tv ip>
# Rezultat je u work\mreza.txt (work\ se nikad ne salje na GitHub: u njemu su imena mreza).
param([Parameter(Mandatory = $true)][string]$TvHost)
Set-Location (Split-Path $PSScriptRoot -Parent)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "${TvHost}:5555"

& $adb connect $dev | Out-Host
for ($i = 0; $i -lt 40 -and "$(& $adb -s $dev get-state 2>$null)".Trim() -ne 'device'; $i++) {
    if ($i -eq 0) { Write-Host 'Na TV-u stikliraj "Always allow" i pritisni OK...' -ForegroundColor Yellow }
    Start-Sleep 3; & $adb connect $dev | Out-Null
}
if ("$(& $adb -s $dev get-state 2>$null)".Trim() -ne 'device') { Write-Host "Stick na $TvHost ne odgovara." -ForegroundColor Red; exit 1 }

$sh = @'
echo "== VREME NA STICKU"; date; uptime
echo "== WI-FI SADA"; dumpsys wifi | grep -m2 -E "mWifiInfo|Wi-Fi is"
echo "== RUTER"; ip route
GW=$(ip route | awk '/default/ {print $3; exit}')
echo "== PING RUTERA $GW (20 puta)"; ping -c 20 -i 0.5 -W 1 $GW | tail -2
echo "== PING INTERNETA 1.1.1.1 (20 puta)"; ping -c 20 -i 0.5 -W 2 1.1.1.1 | tail -2
echo "== MREZE U DOMETU"; dumpsys wifi | sed -n '/Latest scan results:/,/^ *$/p' | head -45
echo "== SACUVANE MREZE"; dumpsys wifi | grep -E '^ *ID: [0-9]+ |^ *SSID: |NetworkSelectionStatus|numAssociation|NETWORK_SELECTION_TEMPORARY|DISABLED_' | head -60
echo "== PREKIDI (dumpsys)"; dumpsys wifi | grep -iE 'DISCONNECT|ROAM|ASSOCIATION_REJECT|AUTHENTICATION_FAILURE' | tail -40
echo "== PREKIDI (logcat)"; logcat -d -v time | grep -E 'CTRL-EVENT-DISCONNECTED|CTRL-EVENT-CONNECTED|TEMP-DISABLED|REENABLED|CTRL-EVENT-ASSOC-REJECT|CTRL-EVENT-BEACON-LOSS|NETWORK_DISCONNECTION' | tail -40
echo "== RSSI ISTORIJA"; dumpsys wifi | grep -iE 'rssi=|rssi:' | tail -25
echo "== PODESAVANJA"; for k in wifi_sleep_policy wifi_scan_always_enabled wifi_scan_throttle_enabled wifi_suspend_optimizations_enabled wifi_networks_available_notification_on captive_portal_mode private_dns_mode; do echo "$k=$(settings get global $k)"; done
echo "== RADIO RADI?"; dumpsys activity services local.cleanyourtv.radio | grep -c ServiceRecord
echo "== ISKLJUCENI MREZNI PAKETI"; pm list packages -d | grep -iE 'wifi|network|connect|captive|wpa|tether'
'@
$tmp = Join-Path $env:TEMP 'mreza.sh'
[IO.File]::WriteAllText($tmp, ($sh -replace "`r`n", "`n"))
& $adb -s $dev push $tmp /data/local/tmp/mreza.sh | Out-Null
Write-Host 'Citam stanje mreze na sticku (oko 30 sekundi)...'
& $adb -s $dev shell sh /data/local/tmp/mreza.sh 2>&1 | Out-File -Encoding utf8 work\mreza.txt
& $adb -s $dev shell rm /data/local/tmp/mreza.sh
Write-Host "Gotovo: work\mreza.txt. Posalji taj fajl Claude-u." -ForegroundColor Green
Start-Process notepad (Resolve-Path work\mreza.txt)
