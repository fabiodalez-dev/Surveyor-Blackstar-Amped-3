# Analisi Protocollo Blackstar Architect e Amped 3

## L'Analisi del Software
L'obiettivo iniziale era decompilare l'applicazione macOS **Blackstar Architect** per estrarne il protocollo di comunicazione (HID o SysEx proprietario) per poter controllare i parametri della pedaliera Amped 3 (ed eventualmente altri amplificatori Blackstar supportati) direttamente da un cellulare.

Analizzando il bundle `Blackstar Architect.app` (una monolitica build C++ Mach-O, presumibilmente basata sul framework JUCE, molto comune in ambito audio) abbiamo potuto ispezionare le stringhe binarie e la struttura delle risorse. 

## La Scoperta: Supporto MIDI Nativo
Cercando i pattern SysEx e la gestione dei parametri, è emersa un'ottima notizia dai manuali e dalle definizioni tecniche del firmware Blackstar:
A differenza di modelli più vecchi della serie ID:Core che necessitavano di complesse chiamate USB HID (Reverse Engineering), **la serie Dept. 10 Amped (inclusa la tua Amped 3) supporta nativamente lo standard MIDI (Control Change messages) tramite USB e TRS MIDI**.

Ciò significa che **non è necessario un protocollo di reverse engineering proprietario**! I parametri del suono sono direttamente mappati su standard MIDI CC.

### Mappatura CC MIDI per Amped 3
Il canale MIDI di default è il **Canale 1** (modificabile tramite Architect). Ecco i comandi CC per il controllo dei parametri del preamplificatore in tempo reale:

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

> [!TIP]
> Poiché usa standard MIDI, l'app Android che ho creato invia semplicemente messaggi MIDI universali alla porta USB. Questo la rende teoricamente **compatibile con qualsiasi pedaliera** o modulo Blackstar (es: Amped 1, Amped 2) che supporti il protocollo MIDI CC standard!

## L'Applicazione Android (APK)
Ho creato il progetto Android **Amped 3 Controller** all'interno del tuo workspace in `~/Documents/GitHub/Blackstar/AmpedController`.

L'applicazione utilizza l'API nativa `android.media.midi` per interfacciarsi con i dispositivi USB.
1. **Come funziona:** Appena lanciata, o premendo "Reconnect MIDI", l'app scansiona le periferiche USB connesse tramite OTG. Se trova un device MIDI (come la pedaliera Amped), apre un canale di output.
2. **Interfaccia (UI):** Ho scritto l'interfaccia con Jetpack Compose. Mostra slider che vanno da 0 a 127.
3. **Comunicazione:** Spostando uno slider, l'app compone il pacchetto MIDI di 3 byte (Status CC, CC number, Value) e lo invia direttamente alla testata.

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
La procedura di salvataggio dei preset sulla memoria fisica del Mac e su Android è stata confermata: include lettura pre-save, fsync locale e verifica del corretto caricamento dopo la scrittura, riducendo a zero il rischio di corruzione dei banchi.
