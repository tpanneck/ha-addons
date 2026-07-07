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
(def humidity-sensor (or (:humidity_sensor opts) "sensor.wohnzimmer_shelly_2_luftfeuchtigkeit"))
(def history-days (int (or (:days opts) 14)))  ;; nur so viel Historie lesen, wie sinnvoll da ist
(def c-mj-estimate (double (or (:c_mj opts) 290.0)))  ;; Speichermasse C [MJ/K] (physikal. Anker); W/K = C/tau

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
  "Innentemperatur + Innenfeuchte ueber HAs History (mit end_time!) + aktuelle /states-Werte.
   Fuellt nebenbei die Sensor-Liste (Discovery aus /states). Gibt {:t_in map :rh_in map}."
  []
  (let [now (-> (java.time.Instant/now) (.truncatedTo java.time.temporal.ChronoUnit/SECONDS))
        start (.toString (.minusSeconds now (* history-days 86400)))
        end (.toString now)
        pt (hist-probe (str "/history/period/" start "?end_time=" end
                            "&filter_entity_id=" sensor "&minimal_response"))
        ph (hist-probe (str "/history/period/" start "?end_time=" end
                            "&filter_entity_id=" humidity-sensor "&minimal_response"))
        t-hist (states->hourly (:states pt))
        rh-hist (states->hourly (:states ph))
        rs (ha-get-raw "/states")
        all (when (= 200 (:status rs)) (try (json/parse-string (str (:body rs)) true) (catch Exception _ nil)))
        sensors (when (sequential? all)
                  (->> all
                       (filter #(str/starts-with? (str (:entity_id %)) "sensor."))
                       (mapv (fn [s] {:id (:entity_id s) :state (:state s)
                                      :unit (get-in s [:attributes :unit_of_measurement])}))
                       (sort-by :id) vec))
        cur-of (fn [ent] (let [c (some (fn [s] (when (= (:entity_id s) ent) s)) all)]
                           (when c (when-let [v (num? (:state c))]
                                     (when-let [h (iso->hour (or (:last_changed c) (:last_updated c)))] {h v})))))
        t-in (merge (or t-hist {}) (cur-of sensor))
        rh-in (merge (or rh-hist {}) (cur-of humidity-sensor))]
    (reset! entities (or sensors []))
    (reset! ha-debug {:hist-status (:status pt) :hist-raw (:raw pt) :days history-days
                      :states-status (:status rs) :sensor-count (count sensors)
                      :hours (count t-in) :sensor sensor})
    (println (str "[aqua] history T=" (:raw pt) " RH=" (:raw ph) " states=" (:status rs)
                  " -> T-Std=" (count t-in) " RH-Std=" (count rh-in)))
    {:t_in t-in :rh_in rh-in}))

(defn parse-hourly [h]
  (when h
    (->> (map (fn [ti te ra wi rh]
                (when (some? te) [ti {:t_out te :solar ra :wind wi :rh_out rh}]))
              (:time h) (:temperature_2m h) (:shortwave_radiation h) (:wind_speed_10m h) (:relative_humidity_2m h))
         (remove nil?)
         (into {}))))

(defn outdoor-hourly
  "Aussen/Strahlung/Wind/Feuchte stuendlich aus Open-Meteo (letzte history-days Tage).
   Null-Stunden fallen raus. Bewusst nur so weit zurueck, wie wir Innen-Daten haben."
  []
  (let [u (str "https://api.open-meteo.com/v1/forecast?latitude=" lat "&longitude=" lon
               "&hourly=temperature_2m,shortwave_radiation,wind_speed_10m,relative_humidity_2m"
               "&past_days=" (min 92 history-days) "&forecast_days=1&timezone=UTC")]
    (or (parse-hourly (:hourly (om-get u))) {})))

;; ---------- Psychrometrie (Magnus) ----------
(defn abs-hum [t rh]   ;; absolute Feuchte [g/m3]
  (when (and t rh)
    (/ (* 216.7 (/ (double rh) 100.0) 6.112 (Math/exp (/ (* 17.62 t) (+ 243.12 t)))) (+ 273.15 t))))
(defn dew-point [t rh] ;; Taupunkt [C]
  (when (and t rh (pos? (double rh)))
    (let [g (+ (/ (* 17.62 t) (+ 243.12 t)) (Math/log (/ (double rh) 100.0)))]
      (/ (* 243.12 g) (- 17.62 g)))))

;; ---------- Archiv (CSV in /share) ----------
(defn read-archive []
  (try
    (when (.exists (java.io.File. archive-file))
      (let [lines (str/split-lines (slurp archive-file))]
        (into {} (for [ln (rest lines) :when (not (str/blank? ln))]
                   (let [[ts ti to so wi ri ro] (str/split ln #"," -1)]
                     [ts {:t_in (num? ti) :t_out (num? to) :solar (num? so) :wind (num? wi)
                          :rh_in (num? ri) :rh_out (num? ro)}])))))
    (catch Exception _ {})))

(defn write-archive [m]
  (try
    (let [p (.getParentFile (java.io.File. archive-file))]
      (when (and p (not (.exists p))) (.mkdirs p)))
    (let [rows (sort-by first m)
          body (str "ts,t_in,t_out,solar,wind,rh_in,rh_out\n"
                    (str/join "\n"
                      (for [[ts v] rows]
                        (str ts "," (or (:t_in v) "") "," (or (:t_out v) "")
                             "," (or (:solar v) "") "," (or (:wind v) "")
                             "," (or (:rh_in v) "") "," (or (:rh_out v) "")))))]
      (spit archive-file body))
    (catch Exception e (println "[aqua] Archiv-Schreibfehler" (.getMessage e)))))

(defn build-archive []
  (let [in (or (indoor-hourly) {})
        t-in (:t_in in) rh-in (:rh_in in)
        out (or (outdoor-hourly) {})
        old (read-archive)
        keys (into #{} (concat (keys old) (keys t-in) (keys rh-in) (keys out)))
        merged (into {} (for [ts keys]
                          [ts (merge (get old ts)
                                     (when-let [o (get out ts)] o)          ;; :t_out :solar :wind :rh_out
                                     (when-let [v (get t-in ts)] {:t_in v})
                                     (when-let [v (get rh-in ts)] {:rh_in v}))]))]
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

;; ---------- Thermisches Modell: Trajektorien-Fit (tau) + Rueckwaerts-Projektion ----------
(defn interp-at [pts t]
  (let [f (first pts) l (last pts)]
    (cond (<= t (first f)) (second f)
          (>= t (first l)) (second l)
          :else (loop [a f more (rest pts)]
                  (let [b (first more)]
                    (if (and (<= (first a) t) (<= t (first b)))
                      (+ (second a) (* (/ (- t (first a)) (double (- (first b) (first a))))
                                       (- (second b) (second a))))
                      (recur b (rest more))))))))

(defn thermal-model
  "Free-run-RC ueber Stundenraster: dT/dt = a*(Tout-T) + b*Solar + g.
   a per Grid-Suche, b,g per OLS. tau=1/a. C = W/K * tau. Rueckwaerts-Projektion von letzter Messung."
  [arch]
  (try
    (let [rows (archive->rows arch)
          in-pts (->> rows (filter :t_in) (map (fn [r] [(:e r) (:t_in r)])) (sort-by first) vec)]
      (when (>= (count in-pts) 24)
        (let [e0 (ffirst in-pts) e1 (first (last in-pts))
              hrs (vec (range e0 (inc e1) 3600))
              om  (into {} (map (fn [r] [(:e r) r]) rows))
              tout (mapv #(get-in om [% :t_out]) hrs)
              sol  (mapv #(or (get-in om [% :solar]) 0.0) hrs)]
          (when (and (> (count hrs) 48) (every? some? tout))
            (let [n (count hrs)
                  tin (mapv #(interp-at in-pts %) hrs)
                  mean (/ (reduce + tin) n)
                  sst (reduce + (map #(let [d (- % mean)] (* d d)) tin))
                  eval-a (fn [a]
                           (let [al (- 1.0 a)]
                             (loop [k 0 tb (double (first tin)) ts 0.0 to 0.0 B [] S [] O []]
                               (if (= k n)
                                 (let [r (mapv - tin B)
                                       s11 (reduce + (map * S S)) s12 (reduce + (map * S O)) s22 (reduce + (map * O O))
                                       sy1 (reduce + (map * S r)) sy2 (reduce + (map * O r))
                                       det (- (* s11 s22) (* s12 s12))
                                       [b g] (if (zero? det) [0.0 0.0]
                                                 [(/ (- (* sy1 s22) (* s12 sy2)) det) (/ (- (* s11 sy2) (* s12 sy1)) det)])
                                       model (mapv (fn [bb ss oo] (+ bb (* b ss) (* g oo))) B S O)
                                       sse (reduce + (map #(let [d (- %1 %2)] (* d d)) tin model))]
                                   {:a a :b b :g g :sse sse})
                                 (recur (inc k)
                                        (+ (* al tb) (* a (nth tout k)))
                                        (+ (* al ts) (nth sol k))
                                        (+ (* al to) 1.0)
                                        (conj B tb) (conj S ts) (conj O to))))))
                  grid (map #(Math/exp %)
                            (map #(+ (Math/log 0.001) (* % (/ (- (Math/log 0.06) (Math/log 0.001)) 100.0))) (range 101)))
                  {:keys [a b g sse]} (apply min-key :sse (map eval-a grid))
                  r2 (if (pos? sst) (- 1.0 (/ sse sst)) 0.0)
                  back (vec (reverse
                             (reduce (fn [acc k]
                                       (conj acc (/ (- (peek acc) (* a (nth tout (dec k)))
                                                       (* b (nth sol (dec k))) g)
                                                    (- 1.0 a))))
                                     [(double (nth tin (dec n)))]
                                     (range (dec n) 0 -1))))
                  tau-h (/ 1.0 a) tau-d (/ tau-h 24.0)
                  wk (/ (* c-mj-estimate 1e6) (* tau-h 3600.0))]   ;; W/K = C / tau
              {:tau-d tau-d :r2 r2 :wk wk :c-mj c-mj-estimate
               :back (zipmap hrs back)})))))
    (catch Exception e (println "[aqua] thermal-model Fehler:" (.getMessage e)) nil)))

;; ---------- State / Loop ----------
(def state (atom {:status "startet..." :archive {} :model nil :series nil}))

(defn refresh! []
  (println "[aqua] Archiv-Update laeuft...")
  ;; Modell bewusst GEPARKT (im Sommer nicht identifizierbar) - nur Daten laden + zeigen.
  (let [arch (build-archive)
        tm (thermal-model arch)]
    (reset! state {:status "ok" :archive arch :updated (str (java.time.OffsetDateTime/now))})
    (println (str "[aqua] fertig: " (count arch) " Std Archiv"
                  (when tm (format "; tau=%.1f d, W/K=%.0f, R2=%.3f" (:tau-d tm) (:wk tm) (:r2 tm)))))))

;; ---------- Web ----------
(defn parse-days [qs]
  (when qs (when-let [m (re-find #"days=(\d+)" qs)] (Integer/parseInt (second m)))))

(defn build-response
  "Antwort fuer /api/data, optional auf die letzten `days` Tage gefenstert
   (Fenster <= ~9 d schneidet das Umzugs-Rauschen weg). Fit wird pro Fenster gerechnet."
  [days]
  (let [arch (:archive @state)
        rows-all (archive->rows arch)
        maxe (when (seq rows-all) (apply max (map :e rows-all)))
        cutoff (when (and days (pos? days) maxe) (- maxe (* days 86400)))
        rows (if cutoff (filterv #(>= (:e %) cutoff) rows-all) rows-all)
        warch (if cutoff (into {} (filter #(when-let [e (hour->epoch (key %))] (>= e cutoff)) arch)) arch)
        s (attach-model rows nil)
        tm (thermal-model warch)
        in-eps (->> rows (filter :t_in) (map :e) sort)
        n-in (count in-eps)
        span-d (when (>= n-in 2) (/ (- (last in-eps) (first in-eps)) 86400.0))]
    (if (empty? s)
      {:time [] :status (:status @state) :days days :ha_debug @ha-debug :sensors @entities}
      {:time  (mapv :e s)
       :t_in  (mapv :t_in s)
       :t_out (mapv :t_out s)
       :solar (mapv :solar s)
       :wind  (mapv :wind s)
       :q_in  (mapv #(abs-hum (:t_in %) (:rh_in %)) s)
       :q_out (mapv #(abs-hum (:t_out %) (:rh_out %)) s)
       :dew_in (mapv #(dew-point (:t_in %) (:rh_in %)) s)
       :t_back (when tm (mapv #(get (:back tm) (:e %)) s))
       :thermal (when tm {:tau_d (:tau-d tm) :r2 (:r2 tm) :wk (:wk tm) :c_mj (:c-mj tm)})
       :indoor_hours n-in :indoor_span_days span-d :days days
       :updated (:updated @state) :status (:status @state)
       :ha_debug @ha-debug :sensors @entities})))

(defn read-web [f ct]
  (try {:status 200 :headers {"Content-Type" ct} :body (slurp (str "web/" f))}
       (catch Exception _ {:status 404 :body "not found"})))

(def index-html (slurp "web/index.html"))

(defn handler [req]
  (case (:uri req)
    "/api/data"           {:status 200 :headers {"Content-Type" "application/json"}
                           :body (json/generate-string (build-response (parse-days (:query-string req))))}
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
