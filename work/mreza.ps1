# Dijagnoza sticka: Wi-Fi (koje mreze vidi i pamti, kako bira) i budjenje posle duzeg mirovanja. Ne menja nista.
#   powershell -ExecutionPolicy Bypass -File work\mreza.ps1 -TvHost <tv ip>
# Rezultat je u work\mreza.txt (work\ se nikad ne salje na GitHub: u njemu su imena mreza).
# Najbolje odmah posle problema: Android cuva samo poslednjih sat-dva zapisa.
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
W=/data/local/tmp/wifi.txt
dumpsys wifi > $W 2>/dev/null
echo "== VREME I RESTART"; date; uptime; echo "razlog poslednjeg paljenja: $(getprop sys.boot.reason) $(getprop ro.boot.bootreason)"
echo "== WI-FI SADA"; grep -m1 "mWifiInfo" $W
echo "== RUTA"; ip route show table all 2>/dev/null | grep -m3 default
echo "== PING INTERNETA 1.1.1.1 (20 puta)"; ping -c 20 -i 0.5 -W 2 1.1.1.1 | tail -2
echo "== SACUVANE MREZE (configKey)"; grep -o 'configKey="[^"]*"[A-Z_]*' $W | sort | uniq -c | sort -rn | head -15
echo "== MREZE U DOMETU (BSSID, MHz, dBm, ime)"; grep -E '^ *([0-9a-f]{2}:){5}[0-9a-f]{2} +[0-9]{4} +-[0-9]+' $W | head -30
echo "== PREKIDI I PRELASCI (poslednjih 40)"; grep -E 'FRAMEWORK_DISCONNECT|CMD_START_CONNECT|NETWORK_DISCONNECTION_EVENT|CMD_IP_REACHABILITY_LOST|CMD_ASSOCIATED_BSSID' $W | tail -40
echo "== IZBOR MREZE IZ RADIJA (logcat)"; logcat -d -v time -s radio:I | tail -30
echo "== DOZVOLE RADIJA"; echo "lokacija ukljucena: $(settings get secure location_mode) (3 = da)"; dumpsys package local.cleanyourtv.radio | grep -E 'ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|versionName' | head -5
echo "== RADIO RADI?"; dumpsys activity services local.cleanyourtv.radio | grep -c ServiceRecord
echo "== NAPAJANJE I EKRAN"; dumpsys power | grep -E 'mWakefulness=|mLastWakeTime|mLastSleepTime|mScreenOffTimeoutSetting|mSleepTimeoutSetting|mAttentiveTimeoutSetting|mIsPowered|mStayOn' | head -12
for k in screen_off_timeout sleep_timeout attentive_timeout; do echo "$k=$(settings get system $k) / $(settings get secure $k)"; done
echo "screensaver=$(settings get secure screensaver_components) ukljucen=$(settings get secure screensaver_enabled)"
echo "== HDMI-CEC"; for k in hdmi_control_enabled hdmi_control_auto_wakeup_enabled hdmi_control_auto_device_off_enabled; do echo "$k=$(settings get global $k)"; done
echo "== PALJENJE/GASENJE EKRANA (events)"; logcat -d -b events -v time | grep -E 'screen_toggled|power_sleep_requested|power_screen_state|am_anr|am_crash|am_low_memory|boot_progress_enable_screen' | tail -40
echo "== ZAMRZAVANJA I PADOVI (dropbox)"; dumpsys dropbox 2>/dev/null | grep -E 'anr|crash|watchdog|tombstone|lowmem' | tail -20
echo "== NOVI PADOVI (crash log)"; logcat -d -b crash -v time | tail -25
echo "== MEMORIJA"; grep -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys meminfo com.gathin.tvhome | grep -m1 -E 'TOTAL'
echo "== ISKLJUCENI PAKETI"; pm list packages -d | sort
rm -f $W
'@
$tmp = Join-Path $env:TEMP 'mreza.sh'
[IO.File]::WriteAllText($tmp, ($sh -replace "`r`n", "`n"))
& $adb -s $dev push $tmp /data/local/tmp/mreza.sh | Out-Null
Write-Host 'Citam stanje sticka (oko 30 sekundi)...'
$ErrorActionPreference = 'Continue'
& $adb -s $dev shell sh /data/local/tmp/mreza.sh 2>$null | Out-File -Encoding utf8 work\mreza.txt
& $adb -s $dev shell rm /data/local/tmp/mreza.sh
Write-Host "Gotovo: work\mreza.txt. Posalji taj fajl Claude-u." -ForegroundColor Green
Start-Process notepad (Resolve-Path work\mreza.txt)
