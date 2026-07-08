# Changelog

## 0.11.19
- Kacheln Frostwaechter + Waermebedarf nach ganz oben (eigene Reihe unter dem Kopf).
- Prognose-"+N Tage"-Kachel entfernt (ueberfluessig).

## 0.11.18
- Prognose auf 14 Tage (forecast_days=14, take-Limit hoch).
- Neue Kachel "Waermebedarf": Summe UA*(Sollwert - T_aussen) ueber die Prognose-Stunden, in denen
  geheizt werden muss -> kWh fuer die naechsten ~14 Tage (fuer +5 C Sollwert). Im Sommer ~0 (aussen
  ueber Sollwert), im Winter der echte Heizbedarf. Nutzt W/K (= C/tau, im fixed-Modus festes tau).

## 0.11.17
- Korrektur Konvention: TAU ist der freie Parameter, C der Anker. fixed-Modus nimmt jetzt festes
  fixed_tau [Tage] (nicht mehr W/K); W/K = C/tau folgt automatisch. Der feste tau speist Modell,
  Prognose UND die kW-Auslegung.
- Sektion 4: "tau aus Daempfung" wird jetzt immer angezeigt (2. unabhaengiger Schaetzer:
  tau = sqrt(Daempfung^2-1)/omega), neben dem Abkling-tau -> Kreuz-Validierung sichtbar.

## 0.11.16
- Kachel "Frostwaechter": Auslegungs-Waermeleistung = UA * (Sollwert - Auslegungs-Aussentemp),
  Default +5 C bei -15 C -> zeigt den max. kW-Bedarf (nur Anzeige, keine Warnung).
- Config model_mode: auto|fixed. Im Winter/Heizbetrieb geht der Freilauf-Fit nicht -> "fixed" nutzt
  feste W/K (fixed_wk) statt zu fitten. Neue Optionen: model_mode, fixed_wk, frost_setpoint, design_outdoor.
- UI: aktiver Fenster-Button (3/7/14/Alle) wird jetzt hervorgehoben.

## 0.11.15
- Tages-Analyse-Tabelle: "Max (h UTC)" war falsch (harmonic-fit-Phase relativ zum Fensteranfang, nicht
  UTC) -> z.B. T_aussen-Max faelschlich 19h statt nachmittags. Jetzt "Max (lokal)" + Tagesgang
  (Spitze-Spitze) direkt aus der gezeichneten Mittelkurve abgeleitet -> konsistent mit dem Chart.
  Daempfung + Lag bleiben (harmonisch, robust - als Verhaeltnis/Differenz offset-unabhaengig, korrekt).

## 0.11.14
- Tagesgang-Charts: Trend-Artefakt entfernt. Zentrieren auf den Tagesmittel liess den Slope INNERHALB
  des Tages stehen -> bei fallendem Trend kippten alle Kurven. Jetzt Hochpass: zentrierten 24h-Mittel
  je Punkt abziehen (24h = 1 Periode -> Tageszyklus bleibt erhalten, nur Drift+Slope weg). Gemessen:
  T_innen-Kipp 0.68 -> ~0, echte Aussen-Zyklen unveraendert. (24h/48h korrekt, 36h wuerde Zyklus daempfen.)

## 0.11.13
- Tagesprofil komplett neu (Klima-Stil), weil die harmonische Rekonstruktion Modell war, nicht Messung:
  Jetzt pro Kanal ALLE einzelnen Tageskurven (24 h, echte Werte) einzeln, jede um ihren Tagesmittel
  auf 0 zentriert, uebereinandergelegt + dick der Mittelwert aller Tage. Zeigt echte Streuung, typische
  Form und Peak-Zeit - keine erfundenen/gefitteten Kurven. 4 Charts (T/RH innen/aussen), x-Achse lokal.

## 0.11.12
- Tagesprofil-Chart: Kurven waren ruppig (v.a. T_innen, bei 14 Tagen schlimmer), weil das naive
  Stunden-Mittel die Mehrtages-Drift (~0.7 K/Woche) als Spruenge reinliess. Jetzt per harmonischer
  Regression je Reihe: Trend (tau+tau^2) abgespalten, Tageszyklus (cos/sin ueber Stunde-des-Tages)
  als glatte 24h-Kurve rekonstruiert. Keine Spruenge; mehr Tage = mehr Stuetzstellen = besser.

## 0.11.11
- Debug-Trace (Sektion 0) wieder entfernt.
- NEU Tagesprofil-Chart in Sektion 4: je Messreihe die gemittelte 24-h-Kurve (Mittel pro Stunde),
  auf 0-100%% der Eigen-Spanne normiert -> zeigt WANN jede Groesse ihr Max hat und wie stark die
  Reihen gegeneinander verschoben sind. Kacheln nennen die reale Spanne (low-high) + Max-Uhrzeit.
  Zeitachse lokal.

## 0.11.10
- ECHTER Fix "Karte/Anzeige zeigt 14.7 (23:00-Nacht-Forecast) statt jetzt": 0.11.9 beschnitt nur den
  NEUEN Wetter-Abruf, aber die Zukunfts-Stunden lagen schon im persistierten Archiv (build-archive
  merged alt+neu, behielt sie). Jetzt wirft build-response ALLE Stunden > aktuelle Stunde aus der
  Messanzeige (Karte, Chart, Trace) - Zukunft kommt nur noch aus der separaten Prognose-Linie.

## 0.11.9
- BUGFIX (Ursache "endet morgens mit 14.7 Grad"): outdoor-hourly zog per forecast_days=1 den REST
  des heutigen Tages als FORECAST mit in die "gemessene" Aussenkurve -> letzte Zeile war 23:00 UTC
  (= 01:00 CEST, Nacht) und die Karte "Aussen T" zeigte diesen Nacht-Forecast statt jetzt. Jetzt
  wird Aussen auf die AKTUELLE Stunde beschnitten; Zukunft nur noch in der Prognose-Linie.
- NEU Sektion 0 "Daten-Trace": Tabelle der letzten Roh-Archivzeilen (Zeit UTC + lokal, Innen, Aussen,
  RH) + Server-Zeit + Anzeige-Ende. Macht sofort sichtbar, bis wann Daten reichen und wo Loecher sind.

## 0.11.8
- Stuendliche Verdichtung ALLER Reihen: sparse Innen-Messungen (Shelly sendet nur bei Aenderung)
  werden zwischen den echten Punkten linear interpoliert und nach der letzten gehalten -> JEDE
  Stunde hat einen Wert (an echten Daten geprueft: 9 sparse -> 58/58 Stunden). Gleiches fuer Aussen.
- Wetter-Lueckenpruefung bei jedem Refresh: loggt fehlende Stundenwerte + neueste fehlende Stunde
  ("[aqua] WARN Wetter-Luecken: N ... neueste fehlende <ts>") -> zeigt sofort, ob Wetter bis jetzt reicht.
- Log druckt die echte Build-Version ("[aqua] BUILD 0.11.8 ...") statt Hardcode "v0.3" -> Update
  beweisbar gelandet.
- Buendelt die vorherigen Anzeige-Fixes: kein Trim, Innen-Overlay/Nowcast, Tail-Forward-Fill.

## 0.11.8
- Log druckt jetzt die ECHTE Build-Version ("[aqua] BUILD 0.11.8 ...") statt des alten Hardcodes
  "v0.3" -> ob ein Update wirklich auf der Box gelandet ist, ist damit im Log beweisbar.
  (Enthaelt alle Fixes: kein Anzeige-Trim, Innen-Overlay/Nowcast, Forward-Fill des sparsen Sensors.)

## 0.11.7
- Innen-Sensor-Sparsity geloest: der Shelly sendet nur bei 0.1-Grad-Aenderung -> im traegen leeren
  Haus stundenlang kein neuer Punkt. Jetzt wird der letzte bekannte Wert stuendlich bis JETZT
  fortgeschrieben (Sensor gilt als aktiv; unveraenderte Temperatur = letzter Wert ist der aktuelle).
  Sicherheitslimit 24 h, damit ein wirklich toter Sensor keine tagelange erfundene Flachlinie erzeugt.

## 0.11.6
- FIX Anzeige brach beim letzten INNEN-Messwert ab (v0.11.2-Trim) -> bei sparsem Innensensor
  verschwand die AKTUELLE Aussentemperatur. Jetzt: volles Archiv (Aussen bis jetzt) + Innen-Prognose
  als Overlay, das die Innen-Luecke [letzter Messwert..jetzt] als Nowcast ueberbrueckt und in die
  Zukunft laeuft. Neue Kachel-Info "Messung vor X h", wenn der Innensensor gerade schweigt.

## 0.11.5
- Linienstaerken angehoben (Handy-Lesbarkeit): Minimum 2 statt 1, Hauptlinien 2.5, Trend 3.

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
