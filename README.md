# AppVolumeManager – spike tecnico

Spike per capire fino a dove arriva il controllo del **volume per singola app** (in stile
Samsung "Volume per app") su Android 17, senza modificare il sistema: usa solo
API `@SystemApi` chiamate con identità shell tramite **Shizuku**.

> Non è l'app finita: serve a raccogliere dati sul campo (vedi la tabella in fondo).

## Come funziona

1. L'app avvia uno **UserService Shizuku** (`VolumeUserService`) in un processo separato
   che gira come shell (UID 2000) o root (UID 0), a seconda di come è avviato Shizuku.
2. Il servizio registra `AudioManager.registerAudioPlaybackCallback()`. Siccome la shell ha
   `MODIFY_AUDIO_ROUTING`, AudioService lo considera privilegiato e gli passa le
   configurazioni **non anonimizzate**: UID del client e `PlayerProxy`.
3. Per ogni player di un UID con un livello impostato chiama
   `AudioPlaybackConfiguration.getPlayerProxy().setVolume(livello / 100f)`:
   - quando il player compare;
   - a ogni cambio di stato;
   - in silenzio a ogni altra callback, per recuperare eventuali reset del sistema.
4. L'app (UI Compose) legge l'elenco dei player e il log dal servizio via AIDL
   (`IVolumeService`), e salva i livelli per package con DataStore. Quando il servizio
   si ricollega, i livelli salvati vengono rimandati.

Il servizio è in modalità *daemon*: continua a funzionare anche chiudendo l'app.
"Riavvia servizio" lo ferma (riportando a 1.0 i player toccati) e lo riavvia.

### Cosa dicono i sorgenti di Android 17

`android.googlesource.com` non era raggiungibile dall'ambiente di sviluppo. Il mirror
GitHub ufficiale di AOSP si ferma ad Android 16 QPR1. Ho quindi letto il fork **LineageOS**,
ramo **`lineage-24.0`**, che è Android 17 (`Build.VERSION_CODES.CINNAMON_BUN = 37`).
È un riferimento quasi identico ad AOSP, ma non è AOSP puro.

| Domanda | Dove | Cosa dice il codice |
|---|---|---|
| Firme invariate? | `frameworks/base: media/java/android/media/PlayerProxy.java`, `AudioPlaybackConfiguration.java` | `PlayerProxy.setVolume(float)` → `IPlayer.setVolume()` sono ancora `@SystemApi`, così come `getClientUid/getPlayerType/getPlayerState/getPlayerProxy`. |
| La shell è privilegiata? | `services/core/.../audio/AudioService.java` (`registerPlaybackCallback`), `packages/Shell/AndroidManifest.xml` | Sì: privilegiato = ha `MODIFY_AUDIO_ROUTING`, e la shell lo dichiara. |
| L'app può sovrascrivere? | `media/java/android/media/PlayerBase.java` | `IPlayer.setVolume` imposta `mVolMultiplier`, che **si moltiplica** col volume dell'app (`mVolMultiplier * mLeftVolume * pan`). In teoria l'app non lo annulla. |
| Player nativi? | `frameworks/av: media/libaudioclient/PlayerBase.cpp`, `media/libaaudio/src/core/AudioStream.cpp` | OpenSL e AAudio hanno un `PlayerBase` nativo. AAudio applica il valore con `setDuckAndMuteVolume`. Per MMAP e offload va verificato sul dispositivo. |
| Reset dal sistema | `services/core/.../audio/PlaybackActivityMonitor.java` (`mutePlayersForCall` / `unmutePlayersForCall`) | Durante una chiamata il sistema fa `setVolume(0)` e poi `setVolume(1.0)`: il nostro livello viene perso fino alla callback successiva. |

## Uso

1. Scarica l'APK dall'ultimo run di **Actions** (artifact `AppVolume-debug-N`) e installalo.
   Tutte le build sono firmate con lo stesso keystore di debug (`app/debug.keystore`),
   quindi si installano una sopra l'altra.
2. Avvia Shizuku (adb o root), apri AppVolume e concedi il permesso.
3. Fai partire l'audio in un'app: comparirà nella lista con uno slider 0-100 e "Muto".
4. Log completi su Logcat:

   ```sh
   adb logcat -s AppVol
   ```

   Lo UserService gira nel processo `com.mattia.appvolume:volume`. Le eccezioni sono
   stampate con lo stack trace completo.

### Tono di prova

La card "Tono di prova" riproduce un 440 Hz con `AudioTrack`. Con l'interruttore
"Ricrea il player ogni 2 s" rilascia e ricrea il player di continuo. Così puoi provare il
punto 4 (salto iniziale) in modo ripetibile: imposta AppVolume stesso al 10% e ascolta
se a ogni ricreazione si sente un picco. La riga del player mostra
"1° set dopo N ms", cioè quanto tempo è passato da quando il servizio ha visto il player
al primo `setVolume`.

## Stack

AGP 9.4.0 · Gradle 9.8.2 · Kotlin 2.4.21 · Compose BOM 2026.09.00 (Material 3) ·
DataStore 1.3.0 · Shizuku API 13.1.5 · HiddenApiBypass 6.2 · compileSdk 37, targetSdk 36,
minSdk 33. La build gira solo in GitHub Actions (`.github/workflows/build.yml`, JDK 17).

## Risultati dei test (da compilare)

Legenda: ✅ sì · ❌ no · ⚠️ parziale (spiegare nelle note)

| App | Tipo player (dal log) | Compare tra i player? | setVolume funziona? | L'app lo sovrascrive? | Salto all'avvio? | Note |
|---|---|---|---|---|---|---|
| YouTube | | | | | | |
| Spotify | | | | | | |
| Chrome | | | | | | |
| Gioco nativo: … | | | | | | |
| App AAudio/Oboe: … | | | | | | |
| Tono di prova (AudioTrack) | | | | | | |
| Tono di prova, ricrea ogni 2 s | | | | | | |
| Dopo una telefonata | | | | | | |
