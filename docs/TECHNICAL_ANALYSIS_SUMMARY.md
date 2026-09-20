# Analisi Protocollo Blackstar Architect e Amped 3

> **Nota di correzione (20 settembre 2026).** Questo documento conserva anche l'ipotesi iniziale del progetto — il controllo via MIDI CC — che le catture hanno poi smentito per l'uso che ne fa l'app. Le sezioni interessate sono marcate come superate: fa fede il protocollo USB HID descritto nel [README](../README.md) e in [CABRIG_DSP_FORMAT.md](CABRIG_DSP_FORMAT.md). Nessuna affermazione qui dentro va citata senza leggere il riquadro che la precede.

## L'Analisi del Software
L'obiettivo iniziale era decompilare l'applicazione macOS **Blackstar Architect** per estrarne il protocollo di comunicazione (HID o SysEx proprietario) per poter controllare i parametri della pedaliera Amped 3 (ed eventualmente altri amplificatori Blackstar supportati) direttamente da un cellulare.

Analizzando il bundle `Blackstar Architect.app` (una monolitica build C++ Mach-O, presumibilmente basata sul framework JUCE, molto comune in ambito audio) ho ispezionato le stringhe binarie e la struttura delle risorse. 

## ~~La Scoperta: Supporto MIDI Nativo~~ — IPOTESI SUPERATA

> **Questa sezione era sbagliata e viene conservata solo come traccia del percorso.** L'idea che bastasse il MIDI CC nasceva dalle stringhe del binario e dalla documentazione Blackstar, non da una cattura. Quando ho intercettato il traffico reale di Architect sulla mia Amped 3, non c'era un solo messaggio MIDI: Architect parla **USB HID**, con report da 64 byte e comandi `0x16` (parametri ampli), `0xa9` (parametri CabRig) e `0xaa`/`0xab`/`0xac` (trasferimento dei coefficienti). L'app usa `UsbManager` con `controlTransfer` SET_REPORT e `UsbRequest` sull'endpoint interrupt, **non** `android.media.midi`: la sincronizzazione con lo stato reale della pedaliera via MIDI non era ottenibile. La tabella qui sotto non è mai stata verificata sull'hardware in questo progetto e non è usata da Surveyor; resta a titolo documentale per chi volesse provare la strada MIDI TRS, che è un problema diverso dal controllo bidirezionale.

### Mappatura CC MIDI per Amped 3 (non verificata, non usata dall'app)
Il canale MIDI di default sarebbe il **Canale 1** (modificabile tramite Architect). Valori tratti dalla documentazione, non da una cattura:

| Parametro | CC# (Control Change) | Range Valore |
| :--- | :---: | :--- |
| Preamp Volume | 2 | 0 - 127 |
| ISF | 3 | 0 - 127 |
| Bass | 4 | 0 - 127 |
| Middle | 5 | 0 - 127 |
| Treble | 6 | 0 - 127 |
| Gain | 7 | 0 - 127 |
| Response (EL84) | 8 | 127 (On) |
| Response (EL34) | 10 | 127 (On) |
| Response (6L6) | 12 | 127 (On) |
| Presence | 15 | 127 (On) |
| Master Volume | 16 | 0 - 127 |

> [!WARNING]
> Il corollario che ne avevo tratto — «compatibile con qualsiasi pedaliera Blackstar» — cade insieme all'ipotesi. L'app filtra su VID `27d4` / PID `0072` e parla un protocollo HID proprietario: la compatibilità con Amped 1 e Amped 2 è **ignota**, non probabile. Servono i descrittori USB di quelle unità e una cattura, non un'assunzione.

## L'Applicazione Android (APK)
Ho creato il progetto Android **Amped 3 Controller** all'interno del tuo workspace in `~/Documents/GitHub/Blackstar/AmpedController`.

> **Aggiornato al trasporto reale.** La prima versione usava `android.media.midi`; quella attuale no.
1. **Come funziona:** l'app cerca via `UsbManager` il dispositivo VID `27d4` / PID `0072`, chiede il permesso, rivendica l'interfaccia HID e interroga la pedaliera con `[07, ...]` per leggerne lo stato completo (52 byte per l'ampli, 84 per il CabRig) prima di mostrare qualsiasi valore.
2. **Interfaccia (UI):** Jetpack Compose, tema carbone/corallo. Gli slider partono dai valori letti dall'hardware, non da un default: finché la lettura non è arrivata non sono manovrabili.
3. **Comunicazione:** muovendo uno slider l'app invia un report da 64 byte `[0x16, offset, 0, 1, valore]` (oppure `0xa9` per il CabRig) con padding a zero, e rilegge lo stato per confermare il valore effettivo.
4. **Nome storico:** il file si chiama ancora `AmpedMidiController.kt`. È un residuo dell'ipotesi MIDI, non una descrizione di cosa fa.

## Come installare l'APK e testarlo
La build dell'APK è in esecuzione in background.
Una volta completata, potrai trovarlo in:
`~/Documents/GitHub/Blackstar/AmpedController/app/build/outputs/apk/debug/app-debug.apk`

1. Trasferisci l'APK sul tuo cellulare Android.
2. Abilita il sideloading (installa app da origini sconosciute) se non l'hai già fatto.
3. Collega la pedaliera Amped 3 al cellulare tramite un cavo/adattatore **USB OTG (On-The-Go)**.
4. Android ti chiederà se vuoi dare i permessi all'app per accedere al dispositivo USB. Accetta.
5. Muovi gli slider dall'app per modificare il Gain, Volume ed equalizzazione in tempo reale!

Se in futuro vorrai aggiungere il controllo Bluetooth (es. WIDI Master Bluetooth MIDI adapter collegato alla porta MIDI TRS della pedaliera), il codice scritto supporta già i dispositivi BLE MIDI nativi di Android!

## Reverse Engineering del CabRig DSP: Oltre il MIDI

Mentre il MIDI ci permette di controllare i potenziometri dell'amplificatore, la gestione delle simulazioni di cassa (CabRig) si affida a un protocollo USB HID proprietario e molto più complesso. Grazie a una profonda analisi del traffico USB e del binario di Architect, è stato finalmente decodificato il formato proprietario!

### I Dati Rilevati
* La libreria di Architect contiene tutte le 288 combinazioni possibili (24 cabinet, 6 microfoni, 2 assi).
* Queste scelte si traducono in **276 payload di coefficienti DSP distinti** (alcune combinazioni condividono i dati per ottimizzazione).
* Il trasferimento via USB avviene in bulk: un header `0xAA` seguito da 5 report `0xAC` (ogni report è di 64 byte).
* Il payload contiene controlli di integrità: i byte 1-2 usano un **CRC-16/XMODEM** (big-endian), il byte 3 indica la lunghezza, e poi iniziano i dati veri e propri.

### La Struttura del DSP
La più grande scoperta è che il CabRig **non utilizza file IR (Impulse Response) WAV tradizionali o convoluzioni lunghe**. 
Invece, il payload finale assemblato (260 byte) è composto da **65 valori float32 little-endian**.
* Il primo valore float è un coefficiente diretto.
* I restanti valori alimentano **16 sezioni compatte di filtri ricorsivi del secondo ordine (biquad)**.
Questa architettura permette di approssimare molto fedelmente le risonanze e la magnitudo del cabinet in modo ultra-efficiente per il processore della pedaliera.

### Tooling ed Esperimenti Futuri
È stato sviluppato uno script (`tools/cabrig_dsp.py`) in grado di decodificare, convalidare i payload catturati, controllare la stabilità dei poli dopo la quantizzazione float32, alterare il gain e **ricostruire i checksum in modo corretto**.
* Questo prova che **è possibile creare pacchetti di coefficienti personalizzati strutturalmente validi** senza dover neanche interpellare Architect!
* Lo strumento possiede anche una modalità sperimentale `--fit-ir` per adattare una risposta in frequenza desiderata (da un file mono.wav) direttamente al banco di poli biquad, rendendo teoricamente possibile caricare curve EQ o IR custom sulla pedaliera.

### Persistenza e Sicurezza
La procedura di salvataggio nei banchi permanenti è documentata ed è stata esercitata sull'hardware dal lato Mac: backup dei sei slot, scrittura, richiamo di un altro slot, richiamo del banco scritto, confronto e ripristino byte per byte (vedi [STORAGE_VERIFICATION.md](STORAGE_VERIFICATION.md)). Su Android la sequenza fa lettura pre-salvataggio, fsync del backup locale, attesa dell'ACK, rilettura e confronto di nome e dati, e dichiara un esito **non confermato** se uno di questi passi fallisce.

Il rischio non è «ridotto a zero» e scriverlo sarebbe falso: la verifica AMP copre i primi nove parametri continui del formato compatto da 15 byte, non tutti gli interruttori, e mancano ancora la prova di ritenzione dopo spegnimento fisico e un salvataggio completo fatto da Android. Fino ad allora vale la regola operativa: backup prima di ogni scrittura, e un backup fallito ferma la scrittura.
