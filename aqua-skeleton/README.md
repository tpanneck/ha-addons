# Aqua Skeleton — minimales HA-Add-on (Deployment-Test)

Zweck: **beweisen, dass ein eigenes Add-on auf die Box kommt und läuft** — noch ohne
Fachlogik. Es startet einen kleinen Webserver (Ingress-Statusseite „Add-on läuft") und
schreibt alle 30 s einen Heartbeat ins Log.

Sprache: **Babashka** (bb). Basis-Image `babashka/babashka` (amd64 + arm64).

## Dateien
- `config.yaml` — Add-on-Manifest (Ingress an, Supervisor baut lokal aus dem Dockerfile).
- `Dockerfile` — `FROM babashka/babashka`, kopiert `run.clj`, startet `bb run.clj`.
- `run.clj` — der Dienst (HTTP-Server + Heartbeat).

## Aufspielen (lokales Add-on)

1. **Ordner auf die Box legen** — die ganze `aqua-skeleton/`-Struktur nach
   `/addons/aqua-skeleton/` auf der HA-Box. Wege:
   - **Samba-Share-Add-on** → Netzlaufwerk `addons` → Ordner reinkopieren, **oder**
   - **File-Editor / Studio-Code-Server-Add-on** → Dateien unter `/addons/aqua-skeleton/`
     anlegen, **oder**
   - **SSH-Add-on** → `scp -r aqua-skeleton root@<box>:/addons/`.

2. **Add-on-Store neu laden:** Einstellungen → Add-ons → Add-on-Store →
   oben rechts ⋮ → **Neu laden**. Danach erscheint unter **„Lokale Add-ons"** das
   „Aqua Skeleton".

3. **Installieren** (Supervisor baut das Image — beim ersten Mal etwas Wartezeit),
   dann **Starten**.

4. **Prüfen:**
   - Tab **Protokoll** → dort steht `HTTP-Server auf 0.0.0.0:8099 gestartet` und alle
     30 s ein `heartbeat`.
   - „**In Seitenleiste anzeigen**" aktivieren → der Menüpunkt öffnet die Ingress-Seite
     mit „✓ Add-on läuft", Uhrzeit und Uptime.

## v0.2 — HA-API
Ab 0.2.0 liest das Add-on über die Supervisor-API einen **Sensor** und zeigt ihn live
(Seite refresht alle 30 s) plus eine Liste aller Temperatur-Sensoren der Box zur Entdeckung.
Welcher Sensor angezeigt wird, steht im Tab **Konfiguration** (`sensor:`), Default ist ein
Ittigen-Temperatursensor. Passenden `entity_id` aus der Liste dort eintragen → Speichern →
Neustart des Add-ons.

## Später: Fleet-Verteilung
Statt lokalem Ordner das Add-on in ein **Git-Repo** legen und in HA als
**Add-on-Repository** eintragen — dann installieren/aktualisieren alle Boxen aus dem Store
(`git push` → Update).
