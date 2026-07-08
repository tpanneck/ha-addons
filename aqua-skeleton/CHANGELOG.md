# Changelog

## 0.11.4
- Fix Deploy: die Aussen-Trendlinie aus 0.11.3 landete durch einen Kopier-Fehler NICHT in
  web/index.html im Repo -> das Image hatte das alte Frontend. Jetzt korrekt eingespielt.

## 0.11.3
- Geglaettete Aussen-Trendlinie (zentrierter 24h-Mittelwert) ueber Messung UND Prognose durchgehend
  im Temperatur-Chart. Killt den Tagesgang -> zeigt das "effektive" Aussen, dem die traege
  Innentemperatur (Haus = Tiefpass ~tau) folgt -> Kopplung Aussen<->Innen visuell abschaetzbar.

## 0.11.2
- FIX Prognose-Anschluss: setzte erst nach dem letzten AUSSEN-Archivpunkt an, der Innensensor
  ist aber sparse (im Test 25 h aelter) -> sichtbare Luecke + vor-integrierter Sprung (-0.65 C).
  Jetzt startet die Prognose stundengenau am letzten INNEN-Messwert und laeuft lueckenlos: erst
  ueber die gemessene Aussentemp der Sensor-Luecke (Nowcast), dann ueber den Open-Meteo-Forecast.
  Anzeige wird bis zum letzten Innen-Messwert beschnitten -> x-Achse monoton, Anschluss-Sprung ~0.

## 0.11.1
- FIX Chart-Glitch: Prognose begann bei heute 00:00 (Open-Meteo past_days=0), also VOR dem letzten
  Archiv-Punkt (Aussen reicht weiter als der sparse Innensensor) -> xall nicht monoton -> uPlot zog
  einen Strich rueckwaerts. Prognose startet jetzt strikt NACH dem letzten Archiv-Punkt.
- Modell-Kurve im Chart ist jetzt der VORWAERTS-Freilauf (= was der Fit minimiert, RMSE 0.16 statt
  0.66 rueckwaerts). Die frueher gezeigte Rueckwaerts-Integration war numerisch instabil (1/(1-a) je
  Schritt) und wich ueber 14 Tage sichtbar ab. Label "Modell rueckw." -> "Modell (Fit)".
- Forecast-Fenster auf 7 Tage angehoben, damit nach dem Trim ~5 Zukunftstage bleiben.

## 0.11.0
- Modell-Korrektur: reines Leitungsmodell dT/dt = (1/tau)(Taussen - Tinnen), OHNE konstanten
  Term g (und ohne g-Korrektur). g war unphysikalisch (Dauer-Abkuehlung ohne Gleichgewicht) und
  machte tau fensterabhaengig (8 vs 19 d) und die Prognose falsch (Innen fiel trotz steigendem Aussen).
  Jetzt: tau robust (~7-8 d ueber Fenster), Prognose physikalisch beschraenkt (laeuft zur Aussentemp).

## 0.10.3
- Prognose schliesst jetzt C1-stetig an (kein Steigungs-Knick): g wird so korrigiert, dass
  die Anfangssteigung der zuletzt beobachteten entspricht (statt historisch verzerrtem g-Drift).

## 0.10.2
- FIX: Prognose kam rueckwaerts raus (falsches reverse) -> Zeitstempel liefen absteigend,
  Kachel zeigte "bis heute", Chart clippte. Jetzt aufsteigend, reicht 5 Tage in die Zukunft.

## 0.10.1
- Fix: Prognose-Darstellung endete "heute" (Chart-Recycling skalierte X-Achse nicht neu) ->
  Chart 1 wird jetzt neu aufgebaut, X-Achse reicht in die Zukunft.
- Prognose Aussen als eigene beschriftete Kurve; Prognose-Kachel zeigt End-Datum.

## 0.10.0
- 5-Tage-Prognose der Innentemperatur: RC-Modell vorwaerts ueber den Open-Meteo-Forecast
  integriert, im Temperatur-Chart als gepunktete Linie in die Zukunft.

## 0.9.0
- Sektion 4 -> Tages-Analyse-Tabelle (harmonische Regression, 24 h): Amplitude+Phase je
  Groesse (T/RH innen/aussen), T-Daempfung + Lag, ohne willkuerlichen Detrend.
- Ehrliche RH-Treiber-Zeile: Innen-RH koppelt an T_innen (Thermometer), nicht an Aussen-RH.

## 0.8.2
- (uebersprungen)

## 0.8.1
- Korrelations-Sektion zeigt jetzt die *interessanten* Zusammenhänge als Streudiagramme:
  T→q (Sorption) und T→RH (Thermometer), plus die Kreuzkorrelation Außen→Innen (Verzug).

## 0.8.0
- Umbau in 4 thematische Grafiken mit je Kacheln + Erklärtext: Temperatur, relative
  Feuchte (+ Schimmel-Schwelle), absolute Feuchte, Korrelationen.
- Korrelationen jetzt LIVE im Add-on gerechnet: corr(T,RH), corr(T,q), Kreuzkorrelation
  Außen→Innen über Verzug, + 2. unabhängiges τ aus Tages-Amplitudendämpfung.

## 0.7.0
- Feuchte-Auswertung: absolute Feuchte innen/außen (Magnus) + Taupunkt innen ins Chart,
  Kacheln (q_in, Δ zu außen, Taupunkt). Zeigt die Wand-Sorption sichtbar (q_in > q_out).
- Neue Option humidity_sensor. Archiv um rh_in/rh_out erweitert (rueckwaerts-kompatibel).
- Kein ACH-Wert: aus Sommerdaten sorptionsdominiert/nicht trennbar (ehrlich vertagt).

## 0.6.1
- Fix: Beim Fensterwechsel wird der Chart nicht mehr neu erzeugt, sondern nur die Daten
  getauscht - abgeschaltete Legenden-Serien bleiben abgeschaltet.

## 0.6.0
- UI-Fenster-Buttons (3 / 7 / 14 / Alle Tage): Fit + Anzeige werden pro Fenster
  live neu gerechnet. Default 7 Tage — schneidet den Sensor-Umzug/Rausch-Anfang weg.
- Fit läuft jetzt pro angefordertem Fenster (/api/data?days=N), nicht mehr fix beim Refresh.

## 0.5.2
- Fix: Options-Default c_mj als Float (290.0) statt Ganzzahl (290) - int/float-Mismatch
  gegen das float-Schema konnte das Update abbrechen lassen.

## 0.5.1
- Thermisches Modell live: Trajektorien-Fit ueber Stundenraster -> τ (gemessen, R²),
  W/K = C/τ (C = Masse als Eingabe/Anker, Option c_mj), Kacheln mit τ/W/K/C/R².
- Rueckwaerts-Kurve: Modell von der letzten Messung rueckwaerts integriert, im Chart
  ueber die gemessene Innenkurve gelegt (Validierung).

## 0.5.0
- Updates laufen jetzt ueber VORGEBAUTE Images aus ghcr.io (GitHub-Action),
  kein lokaler Build mehr auf der Box -> kein Docker-Hub-Rate-Limit, sauberer Pull.
- Innen-Historie zeigt jetzt den echten Zeitraum aus min/max-Zeitstempel
  (statt blind Stunden-Buckets zu zaehlen; der Sensor liefert sparse).
- arch auf aarch64 + amd64 begrenzt (Babashka ist 64-bit).

## 0.4.4
- CHANGELOG.md ergaenzt (HA-Add-on-Konvention; behebt "No changelog found").

## 0.4.3
- Fix History-Backfill: `end_time` mitgeben. Ohne `end_time` liefert HAs
  `/history/period/<start>` nur ein 1-Tages-Fenster ab `start` — dadurch kamen
  0 Innen-Eintraege. Jetzt kommt das volle Fenster (~14 Tage).

## 0.4.2
- Recorder-Gesamtzaehlung (Entities/Punkte) + zweite History-Probe zur Diagnose.
- Sensor-Liste aus /states auf der Seite (Discovery).

## 0.4.1
- Garantierte Innen-Daten ueber den /states-Weg + volle History-Diagnose.

## 0.4.0
- Reiner Daten-Monitor (Modell-Schaetzung geparkt).
- Archiv nach /share (ueberlebt Deinstallieren).

## 0.3.x
- Thermisches Archiv (HA-History + Open-Meteo) + RC-Modell-Fit + uPlot-Chart.

## 0.2.0
- HA-API-Anbindung: Sensor lesen und anzeigen.

## 0.1.0
- Minimales Skelett: Heartbeat + Ingress-Statusseite.
