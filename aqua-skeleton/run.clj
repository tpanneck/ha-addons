(ns run
  "HA-Add-on (Babashka) v0.3 - Thermisches Archiv + Modell-Schaetzung (Edlau).
   - Innentemperatur aus HAs Recorder (History-API)
   - Aussen/Strahlung/Wind aus Open-Meteo (Archiv, ~92 Tage rueckwirkend)
   - stuendliches Archiv als CSV in /data
   - RC-Modell per kleinster Quadrate schaetzen (mit Wind-Term)
   - Visualisierung (uPlot, offline gebuendelt)
   Nur LESEN + RECHNEN. Kein Schalten, keine Prognose in die Zukunft."
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as hc]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------- Konfiguration ----------
(def start-ms (System/currentTimeMillis))
(def port (Integer/parseInt (or (System/getenv "INGRESS_PORT") "8099")))
(def token (System/getenv "SUPERVISOR_TOKEN"))
(def api-base "http://supervisor/core/api")
;; /share ueberlebt auch Deinstallieren (anders als /data).
(def archive-file (or (System/getenv "ARCHIVE_FILE") "/share/aqua-skeleton/archive.csv"))
(def C-assumed-wh 86000.0) ;; angenommene Waermekapazitaet ~86 kWh/K (aus Heizplan) - zum Umrechnen

(defn options []
  (try (json/parse-string (slurp "/data/options.json") true) (catch Exception _ {})))
(def opts (options))
(def lat    (or (:latitude opts) 51.68))
(def lon    (or (:longitude opts) 11.77))
(def sensor (or (:sensor opts) "sensor.wohnzimmer_shelly_2_temperatur"))
(def history-days (int (or (:days opts) 14)))  ;; nur so viel Historie lesen, wie sinnvoll da ist

;; ---------- lineare Algebra (verifiziert) ----------
(defn solve-linear [A b]
  (let [n (count A)
        M0 (mapv (fn [row bi] (conj (vec (map double row)) (double bi))) A b)
        M (loop [M M0 i 0]
            (if (= i n) M
                (let [piv (apply max-key (fn [r] (Math/abs (double (get-in M [r i])))) (range i n))
                      M (if (= piv i) M (-> M (assoc i (M piv)) (assoc piv (M i))))
                      prow (M i) pval (double (prow i))
                      M (reduce (fn [M k]
                                  (let [krow (M k) f (/ (double (krow i)) pval)]
                                    (assoc M k (mapv #(- (double %1) (* f (double %2))) krow prow))))
                                M (range (inc i) n))]
                  (recur M (inc i)))))]
    (reduce (fn [x row]
              (let [r (M row)
                    s (reduce + 0.0 (map (fn [j] (* (double (r j)) (x j))) (range (inc row) n)))]
                (assoc x row (/ (- (double (r n)) s) (double (r row))))))
            (vec (repeat n 0.0))
            (range (dec n) -1 -1))))

(defn ols [xs ys]
  (let [p (count (first xs))
        XtX (vec (for [i (range p)]
                   (vec (for [j (range p)]
                          (reduce + 0.0 (map (fn [x] (* (double (nth x i)) (double (nth x j)))) xs))))))
        Xty (vec (for [i (range p)]
                   (reduce + 0.0 (map (fn [x y] (* (double (nth x i)) (double y))) xs ys))))]
    (solve-linear XtX Xty)))

;; ---------- Zeit-Helfer (UTC-Stunde) ----------
(def hour-fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:00"))
(defn iso->hour [iso]
  (try (-> (java.time.OffsetDateTime/parse iso) .toInstant
           (.atZone java.time.ZoneOffset/UTC)
           (.truncatedTo java.time.temporal.ChronoUnit/HOURS)
           (.format hour-fmt))
       (catch Exception _ nil)))
(defn hour->epoch [ts]
  (try (.toEpochSecond (java.time.LocalDateTime/parse ts) java.time.ZoneOffset/UTC)
       (catch Exception _ nil)))
(defn num? [s] (try (Double/parseDouble (str s)) (catch Exception _ nil)))

;; ---------- HTTP (harte Timeouts, damit die Loop nie haengt) ----------
(defn with-timeout [ms f]
  (let [fut (future (f))
        v (deref fut ms ::timeout)]
    (when (= v ::timeout) (future-cancel fut))
    (when-not (= v ::timeout) v)))

(defn ha-get [path]
  (with-timeout 18000
    (fn []
      (try
        (let [r (hc/get (str api-base path)
                        {:headers {"Authorization" (str "Bearer " token)}
                         :timeout 12000 :throw false})]
          (when (= 200 (:status r)) (json/parse-string (:body r) true)))
        (catch Exception e (println "[aqua] HA-Fehler" path (.getMessage e)) nil)))))

(defn om-get [url]
  (with-timeout 25000
    (fn []
      (try
        (let [r (hc/get url {:timeout 18000 :throw false})]
          (when (= 200 (:status r)) (json/parse-string (:body r) true)))
        (catch Exception e (println "[aqua] Open-Meteo-Fehler" (.getMessage e)) nil)))))

;; Diagnose des letzten HA-History-Aufrufs (Status + Rohpunkte), sichtbar auf der Seite.
(def ha-debug (atom {}))
(def entities (atom []))   ;; alle Sensor-Entities aus /states (Discovery)

(defn ha-get-raw [path]
  (with-timeout 20000
    (fn []
      (try
        (hc/get (str api-base path)
                {:headers {"Authorization" (str "Bearer " token)} :timeout 15000 :throw false})
        (catch Exception e (println "[aqua] HA-Fehler" path (.getMessage e))
               {:status -1 :error (.getMessage e)})))))

;; ---------- Datenquellen ----------
(defn hist-probe [path]
  (let [resp (ha-get-raw path)
        body-str (str (:body resp))
        states (first (try (json/parse-string body-str true) (catch Exception _ nil)))]
    {:status (:status resp) :raw (if (sequential? states) (count states) 0)
     :states states :body body-str}))

(defn states->hourly [states]
  (when (sequential? states)
    (->> states
         (keep (fn [s] (when-let [v (num? (:state s))]
                         (when-let [h (iso->hour (or (:last_changed s) (:last_updated s)))] [h v]))))
         (group-by first)
         (map (fn [[h pairs]] [h (/ (reduce + (map second pairs)) (count pairs))]))
         (into {}))))

(defn indoor-hourly
  "Innentemperatur ueber HAs History mit start UND end_time (sonst nur 1-Tag-Fenster ab start!)
   + aktueller /states-Wert. Fuellt nebenbei die Sensor-Liste (Discovery aus /states)."
  []
  (let [now (-> (java.time.Instant/now) (.truncatedTo java.time.temporal.ChronoUnit/SECONDS))
        start (.toString (.minusSeconds now (* history-days 86400)))
        end (.toString now)
        p (hist-probe (str "/history/period/" start "?end_time=" end
                           "&filter_entity_id=" sensor "&minimal_response"))
        hist (states->hourly (:states p))
        ;; /states: aktueller Wert + komplette Sensor-Liste
        rs (ha-get-raw "/states")
        all (when (= 200 (:status rs)) (try (json/parse-string (str (:body rs)) true) (catch Exception _ nil)))
        sensors (when (sequential? all)
                  (->> all
                       (filter #(str/starts-with? (str (:entity_id %)) "sensor."))
                       (mapv (fn [s] {:id (:entity_id s) :state (:state s)
                                      :unit (get-in s [:attributes :unit_of_measurement])}))
                       (sort-by :id) vec))
        cur (some (fn [s] (when (= (:entity_id s) sensor) s)) all)
        cur-map (when cur (when-let [v (num? (:state cur))]
                            (when-let [h (iso->hour (or (:last_changed cur) (:last_updated cur)))] {h v})))
        merged (merge (or hist {}) cur-map)]
    (reset! entities (or sensors []))
    (reset! ha-debug {:hist-status (:status p) :hist-raw (:raw p) :days history-days
                      :states-status (:status rs) :sensor-count (count sensors)
                      :hours (count merged) :sensor sensor})
    (println (str "[aqua] history raw=" (:raw p) " states=" (:status rs)
                  " sensors=" (count sensors) " -> Innen-Stunden=" (count merged)))
    merged))

(defn parse-hourly [h]
  (when h
    (->> (map (fn [ti te ra wi]
                (when (some? te) [ti {:t_out te :solar ra :wind wi}]))
              (:time h) (:temperature_2m h) (:shortwave_radiation h) (:wind_speed_10m h))
         (remove nil?)
         (into {}))))

(defn outdoor-hourly
  "Aussen/Strahlung/Wind stuendlich aus Open-Meteo (Forecast-API, letzte history-days Tage).
   Null-Stunden fallen raus. Bewusst nur so weit zurueck, wie wir Innen-Daten haben."
  []
  (let [u (str "https://api.open-meteo.com/v1/forecast?latitude=" lat "&longitude=" lon
               "&hourly=temperature_2m,shortwave_radiation,wind_speed_10m"
               "&past_days=" (min 92 history-days) "&forecast_days=1&timezone=UTC")]
    (or (parse-hourly (:hourly (om-get u))) {})))

;; ---------- Archiv (CSV in /data) ----------
(defn read-archive []
  (try
    (when (.exists (java.io.File. archive-file))
      (let [lines (str/split-lines (slurp archive-file))]
        (into {} (for [ln (rest lines) :when (not (str/blank? ln))]
                   (let [[ts ti to so wi] (str/split ln #"," -1)]
                     [ts {:t_in (num? ti) :t_out (num? to) :solar (num? so) :wind (num? wi)}])))))
    (catch Exception _ {})))

(defn write-archive [m]
  (try
    (let [p (.getParentFile (java.io.File. archive-file))]
      (when (and p (not (.exists p))) (.mkdirs p)))
    (let [rows (sort-by first m)
          body (str "ts,t_in,t_out,solar,wind\n"
                    (str/join "\n"
                      (for [[ts v] rows]
                        (str ts "," (or (:t_in v) "") "," (or (:t_out v) "")
                             "," (or (:solar v) "") "," (or (:wind v) "")))))]
      (spit archive-file body))
    (catch Exception e (println "[aqua] Archiv-Schreibfehler" (.getMessage e)))))

(defn build-archive []
  (let [in (or (indoor-hourly) {})
        out (or (outdoor-hourly) {})
        old (read-archive)
        keys (into #{} (concat (keys old) (keys in) (keys out)))
        merged (into {} (for [ts keys]
                          [ts (merge (get old ts)
                                     (when-let [o (get out ts)] o)
                                     (when-let [i (get in ts)] {:t_in i}))]))]
    (write-archive merged)
    merged))

;; ---------- Modell-Schaetzung ----------
(defn fit-model [archive]
  (let [rows (->> archive (sort-by first)
                  (map (fn [[ts v]] (assoc v :ts ts :e (hour->epoch ts)))))
        by-epoch (into {} (map (juxt :e identity) rows))
        ;; Paare (k, k+1h) mit vollstaendigen Daten:
        pairs (for [r rows
                    :let [nxt (get by-epoch (+ (:e r) 3600))]
                    :when (and nxt (:t_in r) (:t_in nxt) (:t_out r) (:solar r) (:wind r))]
                (let [dT   (- (:t_out r) (:t_in r))
                      x    [1.0 dT (:solar r) (* (:wind r) dT)]
                      y    (- (:t_in nxt) (:t_in r))]
                  {:x x :y y}))]
    (when (>= (count pairs) 12)
      (let [beta (ols (map :x pairs) (map :y pairs))
            [g a b c] beta
            preds (map (fn [{:keys [x]}] (reduce + (map * x beta))) pairs)
            ys (map :y pairs)
            ybar (/ (reduce + ys) (count ys))
            ss-res (reduce + (map (fn [y p] (Math/pow (- y p) 2)) ys preds))
            ss-tot (reduce + (map (fn [y] (Math/pow (- y ybar) 2)) ys))
            rmse (Math/sqrt (/ ss-res (count ys)))
            r2 (if (pos? ss-tot) (- 1.0 (/ ss-res ss-tot)) 0.0)]
        {:g g :a a :b b :c c
         :tau-h (when (pos? a) (/ 1.0 a))
         :tau-d (when (pos? a) (/ 1.0 a 24.0))
         :UA0   (* a C-assumed-wh)          ;; W/K  (bei C angenommen)
         :kwind (* c C-assumed-wh)          ;; W/K je km/h
         :Aeff  (* b C-assumed-wh)          ;; m^2 effektiv
         :rmse-step rmse :r2-step r2 :n (count pairs)}))))

(defn archive->rows [archive]
  (->> archive (sort-by first)
       (mapv (fn [[ts v]] (assoc v :ts ts :e (hour->epoch ts))))))

(defn attach-model
  "Haengt die Modell-Kurve (:tm) an die Archiv-Zeilen. Free-run mit Anker am Messwert;
   bei fehlendem Modell ist :tm ueberall nil (Chart zeigt dann nur die Messdaten)."
  [rows model]
  (if-not model
    (mapv #(assoc % :tm nil) rows)
    (let [{:keys [g a b c]} model]
      (loop [rs rows prev nil acc []]
        (if (empty? rs)
          (vec (reverse acc))
          (let [r (first rs)
                cont? (and prev (= (:e r) (+ (:e prev) 3600))
                           (:t_out prev) (:solar prev) (:wind prev) (some? (:tm prev)))
                step (fn [] (let [dT (- (:t_out prev) (:tm prev))]
                              (+ (:tm prev) g (* a dT) (* b (:solar prev)) (* c (:wind prev) dT))))
                tm (cond
                     (and (nil? (:t_in r)) cont?) (step)   ;; free-run durch Innen-Luecken
                     (nil? (:t_in r))             nil
                     (not cont?)                  (:t_in r) ;; Anker am Messwert
                     :else                        (step))
                r' (assoc r :tm tm)]
            (recur (rest rs) r' (conj acc r'))))))))

;; ---------- State / Loop ----------
(def state (atom {:status "startet..." :archive {} :model nil :series nil}))

(defn refresh! []
  (println "[aqua] Archiv-Update laeuft...")
  ;; Modell bewusst GEPARKT (im Sommer nicht identifizierbar) - nur Daten laden + zeigen.
  (let [arch (build-archive)
        series (attach-model (archive->rows arch) nil)
        in-eps (->> arch (filter (comp :t_in val)) keys (map hour->epoch) (remove nil?) sort)
        n-in (count in-eps)
        span-d (when (>= n-in 2) (/ (- (last in-eps) (first in-eps)) 86400.0))]
    (reset! state {:status "ok" :archive arch :model nil :series series
                   :indoor-hours n-in :indoor-span-days span-d
                   :indoor-from (first in-eps) :indoor-to (last in-eps)
                   :updated (str (java.time.OffsetDateTime/now))})
    (println (str "[aqua] fertig: " (count arch) " Std Archiv, " n-in
                  " Innen-Punkte ueber " (when span-d (format "%.1f" span-d)) " Tage"))))

;; ---------- Web ----------
(defn series->json []
  (let [s (:series @state)
        m (:model @state)]
    (if (empty? s)
      {:time [] :model m :status (:status @state) :indoor_hours (:indoor-hours @state)
       :ha_debug @ha-debug :sensors @entities}
      {:time  (mapv :e s)
       :t_in  (mapv #(:t_in %) s)
       :t_out (mapv #(:t_out %) s)
       :solar (mapv #(:solar %) s)
       :wind  (mapv #(:wind %) s)
       :t_model (mapv #(:tm %) s)
       :model m
       :indoor_hours (:indoor-hours @state)
       :indoor_span_days (:indoor-span-days @state)
       :indoor_from (:indoor-from @state) :indoor_to (:indoor-to @state)
       :updated (:updated @state)
       :status (:status @state)
       :ha_debug @ha-debug :sensors @entities})))

(defn read-web [f ct]
  (try {:status 200 :headers {"Content-Type" ct} :body (slurp (str "web/" f))}
       (catch Exception _ {:status 404 :body "not found"})))

(def index-html (slurp "web/index.html"))

(defn handler [req]
  (case (:uri req)
    "/api/data"           {:status 200 :headers {"Content-Type" "application/json"}
                           :body (json/generate-string (series->json))}
    "/uPlot.iife.min.js"  (read-web "uPlot.iife.min.js" "application/javascript")
    "/uPlot.min.css"      (read-web "uPlot.min.css" "text/css")
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body index-html}))

(defn -main [& _]
  (http/run-server handler {:port port :ip "0.0.0.0"})
  (println (str "[aqua] v0.3 auf 0.0.0.0:" port " - Sensor " sensor
                " @ " lat "," lon " - Token " (if (str/blank? (str token)) "FEHLT" "ok")))
  (future
    (loop []
      (try (refresh!) (catch Exception e (println "[aqua] refresh-Fehler" (.getMessage e))))
      (Thread/sleep (* 60 60 1000)) ;; stuendlich
      (recur)))
  @(promise))

(-main)
