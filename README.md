# FotoTag Android 0.2

App Android Java per aggiungere tag IPTC/XMP (`dc:subject`) a più JPEG in un'unica operazione.

## Funzioni
- Selezione multipla con `ACTION_OPEN_DOCUMENT` e `EXTRA_ALLOW_MULTIPLE` (l'app non impone un massimo; il selettore Android o il fornitore file possono imporre limitazioni).
- Libreria locale persistente dei tag creati, con ricerca e checkbox per riutilizzarli.
- Creazione e applicazione massiva di nuovi tag.
- Aggiunta dei tag selezionati senza cancellare i tag XMP preesistenti in ciascuna fotografia.
- Processamento progressivo e conteggio foto riuscite/fallite.
- Salvataggio del JPEG originale solo dopo conferma. Effettuare prima un backup: non tutti i provider Android garantiscono operazioni di scrittura atomiche.

## Limiti
- Solo JPEG, non HEIC/PNG/RAW.
- Non garantisce che la Galleria Samsung indicizzi `dc:subject`: va verificato con un JPEG di prova.
- La libreria dei tag risiede nelle preferenze dell'app, non è sincronizzata tra dispositivi.
- Se la selezione è molto grande, spazio di archiviazione e limitazioni del picker possono incidere sulle operazioni.
- Metadata XMP esteso o multiplo non supportato in scrittura per sicurezza.

## Build GitHub
Caricare il contenuto della cartella del progetto mantenendo `.github/workflows/build.yml`. Il workflow compila gli autotest Java e genera un APK debug, scaricabile dalla pagina Actions > Artifacts. Non è necessario pubblicare sul Play Store.

## Test
`javac -d build/check app/src/main/java/it/fototag/app/JpegXmpEditor.java tests/JpegXmpEditorTest.java && java -cp build/check JpegXmpEditorTest`

Il test Java esercita la lettura/scrittura XMP; per la conferma finale sono necessari installazione, selezione e scrittura su Android reale.
