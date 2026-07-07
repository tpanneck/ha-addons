# Changelog

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
