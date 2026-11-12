# Isti TV setup: cleanyourtv + srpski (ćirilica) + tema Kragujevac + muzika i radio

Ova grana (`kragujevac`) dodaje preko projekta [cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT licenca):

- početni ekran na srpskom, ćirilicom, sa pozdravom po imenu i pet aplikacija u doku; ostale su pod „Све апликације“
  kad se pritisne dole
- 16 fotografija Kragujevca koje se smenjuju po dobu dana, i iste iza sata na screensaveru
- red „Слушате: …“ gore desno: svoja muzika iz foldera, radio stanice ili isključeno, menja se levo/desno
- sopstvene zvuke navigacije

Radi na Android TV / Google TV uređajima (provereno na Xiaomi Mi TV Stick, MiTV-AESP0, Android 10). Ne radi na Samsung
Tizen, LG webOS ni Fire TV.

## Šta ti treba

- Računar na istoj Wi-Fi mreži kao TV, sa Claude Code-om (ili drugim asistentom koji sme da pokreće komande).
- Na TV-u: Podešavanja → Podešavanja uređaja → Osnovni podaci → 7× OK na „Verzija“, pa u Opcijama za programere uključi
  USB debugging (i Network/ADB debugging ako postoji). TV ostavi upaljen.
- Svoje pesme (MP3, M4A…) ako hoćeš muziku u pozadini. Pesme nisu deo ovog repoa.

## Prompt

Otvori Claude Code u praznom folderu sa **kratkom putanjom** (na Windowsu npr. `C:\tv`) i nalepi sledeće, uz svoje
podatke u prva dva reda:

```text
Model TV-a: <npr. Xiaomi Mi TV Stick>
IP adresa TV-a: <npr. 192.168.1.50, ili "nađi je">

Hoću da mi očistiš Android TV i postaviš početni ekran iz grane kragujevac. Radi na pravom uređaju, korak po korak,
proveravaj screenshotovima sa TV-a i pitaj me pre svega što zavisi od mojih želja.

1. Kloniraj https://github.com/shkabomaestro/cleanyourtv-kg u ovaj folder i prebaci se na granu `kragujevac`
   (`git clone -b kragujevac`). Na Windowsu kloniraj sa `git -c core.longpaths=true`, a folder mora imati kratku
   putanju: duge putanje lome pip i Gradle.
2. Pročitaj `work/README.md`: tamo je opisano kako je sve složeno i kako se menja. To su beleške sa jednog TV-a
   (Mi TV Stick), pa adrese i putanje u njima zameni mojim.
3. Proveri alate i pitaj me pre nego što išta instaliraš: JDK 17–22, Android SDK platform 34 i build-tools 34.0.0,
   Python 3 (napravi `tools/venv` i instaliraj `tools/requirements.txt`), `adb` iz platform-tools.
4. Uradi tačno ono što piše u `PROMPT.md` iz repoa, za moj model TV-a: backup pre ičega, pitanja koje aplikacije i
   servise koristim, isključivanje u malim grupama sa screenshotom između, ništa se ne deinstalira. Liste u
   `work/b1-…b6-*.txt` važe samo za Mi TV Stick MiTV-AESP0 sa Androidom 10; za drugi model sastavi svoje po PROMPT.md.
5. Pre builda me pitaj i upiši u `themes/kragujevac/config.json`: ime za pozdrav (`greeting_name`), pet aplikacija za dok
   i njihov redosled (`dock_apps`, imena paketa sa mog TV-a), šta da se sakrije sa početnog ekrana (`hidden_apps`).
   Pitaj i koje radio stanice hoću: `themes/kragujevac/stations.txt`, jedan red `Ime|adresa strima`.
6. Build i instalacija, ovim redom: prvo radio dodatak (`addons/radio`: `gradlew assembleRelease`, pa `adb install -r`),
   tek onda launcher (`launcher/build-and-install`). Launcher dozvolu za dodatak dobija pri svojoj instalaciji, pa
   obrnut redosled ne radi. `-HomeKeyService` dodaj ako Home dugme na daljinskom ne otvara novi ekran (Xiaomi stickovi).
7. Pošalji temu direktno `adb push`-om u `/sdcard/Android/data/com.gathin.tvhome/files/`: `config.json`, `stations.txt`,
   folder `wallpapers` i folder `music` (moje pesme, ako sam ih stavio u `themes/kragujevac/music`). Zatim pokreni
   `tools/theme_restart.py`. Sporo slanje iz `tools/theme.ps1` puca na slabom Wi-Fi-ju.
8. Na TV-u postavi: screensaver na sat iz launchera
   (`settings put secure screensaver_components com.gathin.tvhome/.ClockDream`, isto za `screensaver_default_component`,
   i `screensaver_enabled 1`) i isključi Androidov klik navigacije (`settings put system sound_effects_enabled 0`),
   jer launcher ima svoje zvuke. Ako si u čišćenju isključio Google screensaver (Backdrop) a ovaj nisi postavio, TV će
   posle 15 minuta mirovanja zaspati i izgubiti Wi-Fi.
9. Proveri screenshotovima: početni ekran, strelica dole do „Све апликације“, dva puta gore do reda „Слушате: …“ pa
   levo/desno, da radio svira i da se gasi kad otvorim neku aplikaciju. Zvuke navigacije ne možeš da čuješ: pitaj mene.
10. Na kraju ugasi ADB i Opcije za programere, zamoli me da restartujem TV i proveri `tools/adb-closed.py <ip>`. Na
    Windowsu taj alat ume da javi „did not answer in 3 s“ i kad je port zatvoren; važi samo „is closed: the TV refused
    the connection“.

Stvari koje su se pokazale u praksi:
- Ne radi `am force-stop com.gathin.tvhome`: to gasi servis za Home dugme. Ako se desi, ponovo ga upiši u
  `enabled_accessibility_services`.
- Mali stickovi menije crtaju u 1280×720 iako video ide u 1080p; fotografije veće od toga ne daju više piksela na ekranu.
- Ugrađeni plejer nekih uređaja ne ume AAC+ radio strimove; zato radio dodatak koristi ExoPlayer.
- Firmver nekih uređaja pri svakom paljenju ponovo uključi poneku aplikaciju (na Mi TV Sticku Prime Video). To zabeleži,
  ne isključuj je iznova.
- Na hotspotu sa telefona veza sticka ume da zamre iako piše da je povezan. Radio dodatak to sam leči dok svira
  (proverava ruter i ponovo se prijavljuje na Wi-Fi); zato cilja `targetSdk 28` i to ne treba "popravljati".
- Sve mora da može da se vrati: napravi skriptu po uzoru na `work/restore-all.ps1`.
```

## Ako nećeš Kragujevac

Fotografije pravi `work/kg_grade.py` iz originala sa Wikimedia Commons: naslove fajlova upiši u `work/kg-hd-titles.txt`,
preuzmi ih sa `work/kg_thumbs.py`, a kadrove i natpise promeni u listi `SHOTS` na vrhu `kg_grade.py`. Zamoli Claude-a da
ti nađe slobodno licencirane fotografije tvog grada i prođe taj postupak.

## Licence

- Kod: MIT, kao i izvorni projekat cleanyourtv.
- Fotografije u `themes/kragujevac/wallpapers`: Wikimedia Commons, CC BY-SA; autori i izvori su u
  `themes/kragujevac/CREDITS.md`. Ako ih deliš dalje, podeli i taj fajl.
- Radio dodatak koristi AndroidX Media3 (ExoPlayer), Apache 2.0; preuzima se pri buildu.
