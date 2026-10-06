# TokNative Beta

Client/player Android **nativo** per video TikTok pubblici e URL video diretti.

## Obiettivo
TokNative nasce per evitare il problema principale del progetto: perdita o crash della riproduzione quando si cambia app o si blocca il telefono. Non contiene WebView e non apre il sito TikTok dentro l'app.

### Implementato nella beta 0.1
- UI Android nativa, Java 17, nessuna WebView.
- Riproduzione con `MediaPlayer` Android.
- Player posseduto da un `ForegroundService`, non dall'Activity.
- Audio che continua quando si cambia app o si blocca il telefono.
- Notifica multimediale con Play/Pausa e Stop.
- Surface video riagganciata quando si torna nell'app.
- Salvataggio periodico della posizione di riproduzione.
- Importazione via **Condividi** da un'altra app (`ACTION_SEND`).
- Inserimento manuale di link TikTok pubblico o URL MP4/M3U8.
- Resolver nativo HTTP per alcuni formati pubblici della pagina TikTok.
- Conservazione del link originale e rinnovo del flusso temporaneo dopo 20 minuti.
- Swipe verticale sul video per precedente/successivo.
- Download con `DownloadManager` in `Movies/TokNative`.
- Gli header User-Agent/Referer vengono passati sia al player sia al download.

## Limite importante
TikTok non espone tramite la normale Display API un feed pubblico completo equivalente a "Per Te", né un URL MP4 generale per ogni video. Il resolver incluso usa soltanto dati presenti nella risposta pubblica del link e può smettere di funzionare quando TikTok cambia il formato o introduce challenge aggiuntive.

Non vengono implementati bypass di DRM, contenuti privati, rimozione watermark o aggiramento di controlli di accesso.

## Build con Android Studio
1. Apri la cartella `TokNative`.
2. Usa JDK 17.
3. Installa Android SDK 35.
4. Sincronizza Gradle.
5. Build > Build APK(s).

APK debug previsto:
`app/build/outputs/apk/debug/app-debug.apk`

## Build con GitHub Actions
È incluso `.github/workflows/android.yml`. Su push, il workflow costruisce `app-debug.apk` e lo pubblica come artifact `TokNative-debug-apk`.

## Test consigliato
1. Installa l'APK.
2. Da TikTok usa Condividi > Copia link, oppure Condividi verso TokNative se disponibile.
3. Avvia il video.
4. Passa a un'altra app: l'audio deve continuare.
5. Blocca lo schermo: l'audio deve continuare e comparire nei controlli multimediali.
6. Riapri TokNative: il Surface video deve riagganciarsi al player ancora attivo.
7. Premi `↓` per scaricare il flusso disponibile in `Movies/TokNative`.

## Beta 0.2 - Discover automatico
- L'app non parte più vuota: include un feed Discover immediato.
- Un modulo HTTP nativo prova a recuperare video pubblici da creator/categorie TikTok, senza WebView.
- I video vengono risolti al flusso multimediale solo quando servono, per non rallentare l'avvio.
- Il pulsante `⟳` aggiorna Discover manualmente.
- Arrivando vicino alla fine del feed, TokNative tenta automaticamente un nuovo aggiornamento.
- Se TikTok limita temporaneamente le richieste, rimane disponibile un piccolo feed fallback di URL pubblici.


## Beta 0.3 - Login TikTok / Google
- Pulsante profilo `👤` nel player.
- Integrazione TikTok OpenSDK Login Kit 2.3.1, senza WebView.
- Autenticazione tramite Chrome Custom Tab; se TikTok propone Google, l'utente può scegliere **Continua con Google** nel flusso TikTok.
- PKCE e `state` anti-CSRF generati per ogni login.
- Redirect predefinito: `https://hgwells2001.github.io/TokNative---Native-Android-TikTok-Client/callback/`.
- Configurazione `client_key` e URL backend direttamente dalla schermata Profilo.
- Il `client_secret` non viene mai inserito nell'APK.
- Incluso `server/cloudflare-worker.js` come esempio minimale per lo scambio server-side del codice OAuth e lettura del profilo.

### Configurazione richiesta nel TikTok Developer Portal
1. Crea/usa una TikTok Developer App e aggiungi **Login Kit**.
2. Registra Android package `it.toknative.app`.
3. Registra MD5 e SHA-256 del certificato con cui distribuisci l'APK.
4. Registra il redirect HTTPS indicato sopra.
5. Abilita almeno `user.info.basic`; per i propri video abilita anche `video.list`.
6. Inserisci la sola `client_key` in TokNative > Profilo > Configura Login Kit.

Per una sessione persistente, configura anche un backend HTTPS che esponga `POST /tiktok/exchange`. Il client secret e gli eventuali refresh token devono restare server-side.