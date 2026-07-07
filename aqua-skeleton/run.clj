(ns run
  "Minimales HA-Add-on-Skelett (Babashka).
   Zweck: beweisen, dass ein eigenes Add-on auf die Box kommt und laeuft.
   - kleiner HTTP-Server fuer die Ingress-Statusseite
   - Heartbeat ins Log (in den Add-on-Logs sichtbar)
   Noch KEINE HA-API-Anbindung - das ist der naechste Schritt."
  (:require [org.httpkit.server :as http]))

(def start-ms (System/currentTimeMillis))
(def port (Integer/parseInt (or (System/getenv "INGRESS_PORT") "8099")))

(defn uptime-str []
  (let [s (quot (- (System/currentTimeMillis) start-ms) 1000)]
    (format "%dh %02dm %02ds" (quot s 3600) (mod (quot s 60) 60) (mod s 60))))

(defn page []
  (str "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>Aqua Skeleton</title>"
       "<style>body{font-family:system-ui,-apple-system,sans-serif;margin:2rem;color:#12253a}"
       "h1{font-size:1.4rem}.ok{color:#0a7d28;font-weight:600}"
       "code{background:#eef2ff;padding:.12rem .4rem;border-radius:5px}"
       "li{margin:.3rem 0}</style></head><body>"
       "<h1>&#127754; Aqua Skeleton</h1>"
       "<p class=\"ok\">&#10003; Add-on laeuft.</p>"
       "<ul>"
       "<li>Zeit auf der Box: <code>" (str (java.time.ZonedDateTime/now)) "</code></li>"
       "<li>Uptime: <code>" (uptime-str) "</code></li>"
       "<li>Ingress-Port: <code>" port "</code></li>"
       "</ul>"
       "<p>Naechster Schritt: Anbindung an die HA-API (Sensoren lesen, Aktoren schalten).</p>"
       "</body></html>"))

(defn handler [_req]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (page)})

(defn -main [& _]
  (http/run-server handler {:port port :ip "0.0.0.0"})
  (println (str "[aqua-skeleton] HTTP-Server auf 0.0.0.0:" port " gestartet"))
  (future
    (loop []
      (Thread/sleep 30000)
      (println (str "[aqua-skeleton] heartbeat - uptime " (uptime-str)))
      (recur)))
  @(promise)) ;; Hauptthread offen halten

(-main)
