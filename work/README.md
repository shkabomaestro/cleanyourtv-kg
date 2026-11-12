# Mi TV Stick (MiTV-AESP0): šta je urađeno i kako se vraća

TV: Xiaomi Mi TV Stick `MiTV-AESP0` (aquaman), Android 10 (SDK 29), firmver QT.2514.
Sve komande se pokreću iz ovog foldera repoa (`Dokumenti\cleanyourtv`) u PowerShell-u.
Za sve ADB komande na TV-u mora biti uključen **Developer options → USB debugging**.

```powershell
$env:TV_HOST = '<tv ip>'
```

## Šta je isključeno

Sve je **isključeno** (`pm disable-user`), ništa nije deinstalirano. Spisak po grupama je u `work\b1-*.txt` … `work\b6-*.txt`,
a sve zajedno u `work\debloat-all.txt`:

1. Telemetrija: Xiaomi analytics/statistic, Google feedback, bug report sender
2. PatchWall, Mi TV+, preporuke i automatske instalacije partnerskih aplikacija
3. Prime Video, Play Igre, Play Music, YouTube Music, Play Filmovi, Xiaomi pravni ekran
4. Xiaomi servisi za PatchWall (mitv.service, download, video plejer)
5. Setup/nalozi/backup/štampanje (kontakti, kalendar, sync, print spooler, CTS…)
6. Stock screensaveri (Backdrop, Basic dreams)

**Ostalo uključeno** (po tvom izboru): glasovni asistent, Chromecast, Miracast, ažuriranja firmvera, Netflix, YouTube,
tvoje aplikacije, daljinski i Bluetooth, Play Store i Play Services, setup wizard (setupwraith, jer je on sačuvao Wi-Fi mrežu).

Napomena: firmver ovog sticka (QT.2514) **pri svakom pokretanju ponovo uključi Prime Video** (provereno u dva restarta).
To nije greška čišćenja; ne isključuj ga iznova posle svakog restarta. Može ostati kao pločica u mreži aplikacija.

## Kako ponovo uključiti jednu aplikaciju

```powershell
tools\venv\Scripts\python.exe tools\tv.py 'pm enable com.amazon.amazonvideo.livingroom'
```
(zameni ime paketa; imena su u `work\debloat-all.txt`). Ili na TV-u: Podešavanja → Aplikacije → Prikaži sistemske → aplikacija → Omogući.

## Vrati sve kako je bilo

```powershell
powershell -ExecutionPolicy Bypass -File work\restore-all.ps1
```
Ponovo uključuje sve iz `work\debloat-all.txt`, vraća Google TV početni ekran i DNS na automatski. Pa restart TV-a.
Originalni backup (spisak paketa, getprop, sva podešavanja) je u `backup\`.

## Novi početni ekran (launcher)

Izgradnja i instalacija (posle svake izmene `config.xml`):

```powershell
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost <tv ip> -HomeKeyService
```
`-HomeKeyService` uvek navedi: Android ga gasi pri svakoj reinstalaciji, a bez njega Home dugme na daljinskom ne radi.
Potrebni su JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17…`, postavi `JAVA_HOME` ako script ne nađe) i Android SDK 34.

### Tema „Kragujevac“ (ime, dok, fotografije)

Izgled početnog ekrana je u `themes\kragujevac\`. Posle svake izmene pošalji temu na TV (bez builda, traje oko minut):

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv ip> themes\kragujevac
```

U `themes\kragujevac\config.json`:

- `greeting_name` — ime posle pozdrava („ДОБРО ВЕЧЕ, ИМЕ“). Prazno = samo pozdrav.
- `dock_apps` — 5 aplikacija na početnom ekranu i njihov redosled (ime paketa nađeš sa `tools\tv.py 'pm list packages -3'`);
  `columns` = broj mesta (4–7).
- `hidden_apps` — aplikacije koje se ne vide na početnom ekranu (Play prodavnica, Send Files, Prime Video). I dalje se
  otvaraju iz dugmeta „Све апликације“ ispod doka (strelica dole). Obriši red da se aplikacija vrati na početni ekran.
- `wallpaper_moods` — koje fotografije idu ujutru (`morning`: od izlaska sunca do podneva), popodne (`evening`: do pola sata
  posle zalaska) i noću (`night`). Izbaci ime fajla iz liste da se ta fotografija ne prikazuje; ostavi samo jednu da stoji stalno.
- `wallpaper_rotation_minutes` — na koliko minuta se menja fotografija unutar istog doba dana (10).
- `wallpaper_captions` — natpis mesta ispod dugmadi gore desno; tekstovi su u `wallpapers\captions.txt`.
- `advanced_effects`, `dock_even_padding`, `icon_only_buttons` — Apple TV detalji (podignuta pločica, ravnomeran dok, okrugla
  dugmad). Ako početni ekran postane trom, stavi `advanced_effects` na `false`.
- `clock_wallpaper` — screensaver je sat preko iste fotografije.

Fotografije (30 komada, 1920×1080) pravi `work\kg_grade.py` iz originala u `work\kg-hd\`: iseca na 16:9, pojačava boje po dobu
dana i zatamnjuje vrh i dno da se sat i dok čitaju. Spisak kadrova je lista `SHOTS` na vrhu skripte (broj originala je iz
`work\kg-hd\index.tsv`). Noćne: V3, Saborna crkva i teatar su prave noćne fotografije,
gimnazija i fića su dnevne obrađene u noć („dusk“). Izgled „day“ ide i ujutru i popodne. Skripta odbija izvor koji posle
isecanja nema bar 1920×1080, a gornji levi ugao blago zatamnjuje da se sat i datum čitaju.
Stick menije crta u 1280×720 (HDMI izlaz je 1080p), pa launcher sliku smanji na tu veličinu; veći fajl ne daje više piksela na
ekranu. Sve su sa Wikimedia Commons pod CC BY-SA; autori su u `themes\kragujevac\CREDITS.md`, pregled je `work\kragujevac-opcije.jpg`.

Nova fotografija: dodaj naslov sa Commons-a u `work\kg-hd-titles.txt`, pa
```powershell
tools\venv\Scripts\python.exe -I work\kg_thumbs.py work\kg-hd-titles.txt work\kg-hd 2560
```
dodaj red u `SHOTS`, pa
```powershell
tools\venv\Scripts\python.exe -I work\kg_grade.py
```

Slanje fotografija je preko `tools\theme.ps1` sporo i na ovom Wi-Fi-ju često pukne. Brže (10 sekundi) je direktno:
```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $d='/sdcard/Android/data/com.gathin.tvhome/files'
& $adb connect <tv ip>:5555; & $adb shell "rm -f $d/wallpapers/*.jpg"
& $adb push "themes\kragujevac\wallpapers\." "$d/wallpapers/"; & $adb push themes\kragujevac\config.json "$d/config.json"
$env:TV_HOST='<tv ip>'; cd tools; venv\Scripts\python.exe -I theme_restart.py; cd ..
```

Dugme „Све апликације“ se ne vidi na početnom ekranu; pojavi se kad pritisneš dole iz doka. To je moja izmena u
`launcher\app\src\main\res\layout\row_app_library.xml` (red je viši, dugme na njegovom dnu); original je u komentaru tog fajla.

### Screensaver i spavanje

Screensaver je sat iz launchera (`screensaver_components = com.gathin.tvhome/.ClockDream`), pali se posle 15 min mirovanja, a
stick ide na spavanje posle 30 min, kao u originalu. Dok je Google Backdrop bio isključen a ništa drugo postavljeno, stick je
posle 15 min umesto screensavera odlazio na spavanje i gasio Wi-Fi. `work\restore-all.ps1` vraća Backdrop.

### Muzika i radio u pozadini

Gore desno, iznad dugmadi za mrežu i podešavanja, piše „Слушате: …“ uz belu ikonicu radija. Do tog reda dolaziš strelicom
gore sa tih dugmadi, a levo/desno menja izvor: Моја музика → stanice iz liste → Радио искључен. Zvuk ide dok si na
početnom ekranu i na screensaveru; kad otvoriš aplikaciju, gasi se posle sekunde-dve i vraća se kad se vratiš. To su moje
dopune launchera (`HomeMusic.java`, `RadioIcon.java` i deo `MainActivity.java` u `launcher\app\src\main\java\com\gathin\tvhome\`).

**Radio stanice** su u `themes\kragujevac\stations.txt`, jedan red po stanici: `Ime|adresa strima` (ime onako kako želiš da piše
na ekranu). Posle izmene pošalji fajl na TV:
```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect <tv ip>:5555; & $adb push themes\kragujevac\stations.txt /sdcard/Android/data/com.gathin.tvhome/files/stations.txt
```
Adresa mora da bude direktan strim (počinje sa `http`, obično se završava na `.mp3` ili `/;`), ne stranica sajta. Iz `.pls` ili
`.m3u` fajla uzmi red `File1=...`. Sam launcher nema pristup internetu: stanice pušta mali dodatak bez ekrana
(`addons\radio`, paket `local.cleanyourtv.radio`), kome launcher samo kaže šta da pusti. Dodatak koristi ExoPlayer, jer
sistemski plejer ovog sticka odbija AAC+ strimove (Radio Kragujevac); MP3 i AAC stanice zato rade obe. Builduje se u `addons\radio`
sa `.\gradlew.bat assembleRelease` i instalira sa `& $adb install -r addons\radio\app\build\outputs\apk\release\app-release.apk`,
i to **pre** launchera, jer launcher dozvolu za njega dobija pri svojoj instalaciji.

**Kad veza zamre.** Na ovom sticku, na hotspotu sa telefona, veza ume da „zamre“: TV i dalje piše da je povezan, nema
nikakvog događaja prekida, ali paketi ne prolaze ni u jednom smeru, po više minuta, dok se Wi-Fi ne ugasi i upali. Android to
ne primeti, pa se sam ne povezuje ponovo. Zato radio dodatak, dok svira: drži Wi-Fi u punoj snazi (WifiLock), na svakih 5 s
proverava da li ruter odgovara i, ako tri pokušaja zaredom ostanu bez odgovora, napusti mrežu i ponovo joj se pridruži (Android
tada bira najbolju sačuvanu mrežu u dometu). Pita samo ruter, pa pad interneta ili same stanice ne dira Wi-Fi. Zbog toga
dodatak cilja Android 9 (`targetSdk 28`): od Androida 10 obična aplikacija više ne sme da traži ponovno povezivanje.
To radi samo dok radio svira; za ostale aplikacije i dalje pomaže ručno gašenje i paljenje Wi-Fi-ja.
Plejer počinje tek kad ima 4 s zvuka u rezervi (8 s posle zastoja), da kratki gubici na mobilnoj vezi ne seku zvuk.

**Moja muzika** su audio fajlovi (MP3, M4A, OGG, FLAC, WAV) iz foldera `music` na TV-u, po abecednom redu imena, ukrug.
Prazan folder = tišina.

Pesme držiš u `themes\kragujevac\music\`. Posle dodavanja ili brisanja pošalji ih na TV (briše stare, šalje sve iz foldera):
```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $m='/sdcard/Android/data/com.gathin.tvhome/files/music'
& $adb connect <tv ip>:5555; & $adb shell "rm -rf $m; mkdir -p $m"; & $adb push "themes\kragujevac\music\." "$m/"
```
Nova lista važi od sledeće pesme, bez restarta. Redosled menjaš brojem na početku imena (`01 ...mp3`, `02 ...mp3`).
Jačina ide dugmetom za zvuk na daljinskom, kao za sve ostalo.

### Temperatura ispod sata

Ispod sata piše trenutna temperatura u Kragujevcu sa ikonicom: sunce (vedro), oblak (oblačno, magla) ili oblak sa kapima
(kiša, sneg, pljusak). Izvor su zvanična merenja RHMZ-a na stanici Kragujevac (broj 13278), koja se osvežavaju na sat:
`https://www.hidmet.gov.rs/latin/osmotreni/index.rss`. To je izmerena vrednost, ne prognoza. Podatak preuzima radio dodatak
(`WeatherProvider.java`; launcher nema internet), najviše jednom u 20 minuta, a launcher ga pita kad se otvori početni ekran
i zatim na svakih 20 minuta dok je otvoren.
Ako nema merenja mlađeg od 3 sata ili nema dodatka, red se ne prikazuje. Za drugi grad promeni `STATION` u tom fajlu na
„ID stanice“ iz istog izvora. Ikonicu bira `Weather.kind` po opisu vremena iz izvora.

### Kvalitet vazduha (IQAir)

Pored temperature stoji „Ваздух 57“ sa tačkom u boji indeksa (zelena do 50, žuta do 100, narandžasta do 150, crvena do 200,
ljubičasta do 300, tamnocrvena preko toga). To je američki AQI koji IQAir daje za Kragujevac, osvežava se jednom na sat.
IQAir traži lični ključ, pa se red ne prikazuje dok ga nema:

1. Na `https://dashboard.iqair.com/personal/api-keys` napravi nalog i besplatan ključ („Community“ plan).
2. Upiši ključ u jedan red u fajl `work\iqair-key.txt` (folder `work` se ne deli i ne ide u fork).
3. Pošalji ga na TV:
```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $k='/sdcard/Android/data/local.cleanyourtv.radio/files'
& $adb connect <tv ip>:5555; & $adb shell "mkdir -p $k"; & $adb push work\iqair-key.txt "$k/iqair-key.txt"
```
Vrednost se pojavi kad se sledeći put otvori početni ekran (najkasnije za 20 minuta). Drugi grad: adresa `AIR` u
`WeatherProvider.java`, sa imenima kako ih IQAir piše na svojoj stranici grada.

### Zvuci navigacije

Početni ekran ima svoja dva zvuka (pomeranje i OK), fajlovi `launcher\app\src\main\res\raw\nav_move.wav` i `nav_select.wav`.
Pravi ih `work\make_nav_sounds.py` (visina tona, trajanje i jačina su brojevi u skripti); posle izmene treba build-and-install.
Da ne bi svirao i Androidov klik, na TV-u je isključeno Podešavanja → Zvuk → Sistemski zvuci
(`settings put system sound_effects_enabled 0`), pa meniji van početnog ekrana više ne klikću. `work\restore-all.ps1` to vraća.

Vraćanje na izgled bez teme: `tools\theme.ps1 -TvHost <tv ip> -Reset`.

### Srpski (ćirilica)

Kompletan prevod je u `launcher\app\src\main\res\values-sr\strings.xml`. TV je na jeziku `sr-RS`, pa se koristi automatski.
Posle izmene teksta treba ponovo build-and-install (komanda gore), pa ponovo poslati temu.
Tehničke skraćenice (Wi-Fi, VPN, CPU, MB, GB, OK) namerno su ostale latinicom. Noću piše „Добро вече“, a ne „Лаку ноћ“.

Napomena: APK sa srpskim prevodom ima 597.877 B, malo preko granice od 576 KB koju repo traži za svoje zvanične izmene.
Za tvoj TV to ništa ne znači; važi samo ako bi prevod slao autoru repoa.

## AdGuard DNS

Uključen Private DNS `dns.adguard-dns.com` (blokira reklame i praćenje, **ne** blokira YouTube reklame). Isključivanje:

```powershell
tools\venv\Scripts\python.exe tools\tv.py 'settings put global private_dns_mode opportunistic'
```

## Zatvaranje ADB-a

Na kraju su USB debugging i Developer options isključeni. Za bilo koju komandu iz ovog fajla ponovo uključi
Developer options (Podešavanja → Podešavanja uređaja → Osnovni podaci → 7× OK na „Verzija“) i USB debugging.

Kad završiš, ugasi ih ponovo (ili preko ADB-a: `adb shell "settings put global development_settings_enabled 0; settings put global adb_enabled 0"`)
i restartuj stick. Provera da je port zatvoren: `tools\venv\Scripts\python.exe tools\adb-closed.py <tv ip>`. Na ovom
računaru taj alat često javi „did not answer in 3 s“ i kad je port zatvoren, jer Windows odbijenu vezu prijavi tek posle
oko 3,5 s; tada važi samo poruka „is closed: the TV refused the connection“ dobijena sa dužim čekanjem.
Ovlašćenje ovog računara za ADB ostaje zapamćeno na sticku dok ga ne opozoveš u Opcijama za programere
(„Opozovi ovlašćenja za USB otklanjanje grešaka“); dok je ADB ugašen, ono ničemu ne služi.
