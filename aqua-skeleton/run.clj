(ns run
  "HA-Add-on-Skelett (Babashka) - Schritt 2: HA-API-Anbindung.
   - liest ueber die Supervisor-API einen konfigurierbaren Sensor
   - zeigt ihn live (Seite refresht alle 30 s)
   - listet zur Entdeckung alle Temperatur-Sensoren der Box
   Token kommt automatisch via SUPERVISOR_TOKEN (config.yaml: homeassistant_api: true)."
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as hc]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def start-ms (System/currentTimeMillis))
(def port (Integer/parseInt (or (System/getenv "INGRESS_PORT") "8099")))
(def token (System/getenv "SUPERVISOR_TOKEN"))
(def api-base "http://supervisor/core/api")

(defn options []
  (try (json/parse-string (slurp "/data/options.json") true)
       (catch Exception _ {})))

(def configured-sensor
  (or (:sensor (options)) "sensor.shellyhtg3_80b54e335734_temperatur"))

(defn ha-get [path]
  (try
    (let [resp (hc/get (str api-base path)
                       {:headers {"Authorization" (str "Bearer " token)}
                        :throw false})]
      (when (= 200 (:status resp))
        (json/parse-string (:body resp) true)))
    (catch Exception e
      (println (str "[aqua-skeleton] HA-API-Fehler " path ": " (.getMessage e)))
      nil)))

(defn fmt-state [s]
  (when s
    {:entity  (:entity_id s)
     :name    (get-in s [:attributes :friendly_name] (:entity_id s))
     :value   (:state s)
     :unit    (or (get-in s [:attributes :unit_of_measurement]) "")
     :changed (:last_changed s)}))

(defn temp-sensors []
  (let [all (ha-get "/states")]
    (when (sequential? all)
      (->> all
           (filter #(str/starts-with? (str (:entity_id %)) "sensor."))
           (filter #(str/includes? (str/lower-case (str (:entity_id %))) "temperatur"))
           (map fmt-state)
           (sort-by :entity)))))

(defn uptime-str []
  (let [s (quot (- (System/currentTimeMillis) start-ms) 1000)]
    (format "%dh %02dm %02ds" (quot s 3600) (mod (quot s 60) 60) (mod s 60))))

(defn esc [x] (-> (str x) (str/replace "&" "&amp;") (str/replace "<" "&lt;")))

(defn page []
  (let [tok? (and token (not (str/blank? token)))
        main (when tok? (fmt-state (ha-get (str "/states/" configured-sensor))))
        temps (when tok? (temp-sensors))]
    (str
     "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<meta http-equiv=\"refresh\" content=\"30\">"
     "<title>Aqua Skeleton</title>"
     "<style>body{font-family:system-ui,-apple-system,sans-serif;margin:2rem;color:#12253a}"
     "h1{font-size:1.4rem}h2{font-size:1.05rem;margin-top:1.6rem}"
     ".ok{color:#0a7d28;font-weight:600}.err{color:#b02020;font-weight:600}"
     ".big{font-size:2.6rem;font-weight:700;margin:.3rem 0}"
     "code{background:#eef2ff;padding:.1rem .35rem;border-radius:5px;font-size:.85em}"
     "table{border-collapse:collapse;margin-top:.4rem}td,th{border:1px solid #dde;padding:.25rem .6rem;text-align:left;font-size:.9rem}"
     "small{color:#667}</style></head><body>"
     "<h1>&#127754; Aqua Skeleton &mdash; HA-API</h1>"
     (cond
       (not tok?)
       "<p class=\"err\">Kein SUPERVISOR_TOKEN &mdash; ist <code>homeassistant_api: true</code> gesetzt?</p>"
       (nil? main)
       (str "<p class=\"err\">Sensor <code>" (esc configured-sensor)
            "</code> nicht gefunden oder API nicht erreichbar.</p>"
            "<p><small>Passenden Namen aus der Liste unten in die Add-on-Konfiguration eintragen.</small></p>")
       :else
       (str "<p>Gewaehlter Sensor <code>" (esc (:entity main)) "</code>:</p>"
            "<div class=\"big\">" (esc (:value main)) " " (esc (:unit main)) "</div>"
            "<p>" (esc (:name main)) "<br><small>Stand: " (esc (:changed main)) "</small></p>"))
     (when (seq temps)
       (str "<h2>Temperatur-Sensoren auf der Box (" (count temps) ")</h2>"
            "<table><tr><th>Wert</th><th>Name</th><th>entity_id</th></tr>"
            (str/join
             (for [t temps]
               (str "<tr><td>" (esc (:value t)) " " (esc (:unit t)) "</td>"
                    "<td>" (esc (:name t)) "</td>"
                    "<td><code>" (esc (:entity t)) "</code></td></tr>")))
            "</table>"
            "<p><small>Tipp: den passenden <code>entity_id</code> in der Add-on-Konfiguration als <code>sensor</code> setzen.</small></p>"))
     "<hr><p><small>Uptime " (uptime-str) " &middot; Seite aktualisiert sich alle 30 s.</small></p>"
     "</body></html>")))

(defn handler [_req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (page)})

(defn -main [& _]
  (http/run-server handler {:port port :ip "0.0.0.0"})
  (println (str "[aqua-skeleton] HTTP-Server auf 0.0.0.0:" port " gestartet"
                " - Sensor: " configured-sensor
                " - Token: " (if (and token (not (str/blank? token))) "vorhanden" "FEHLT")))
  (future
    (loop []
      (Thread/sleep 30000)
      (println (str "[aqua-skeleton] heartbeat - uptime " (uptime-str)))
      (recur)))
  @(promise))

(-main)
