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
(def model-mode (or (:model_mode opts) "auto"))       ;; "auto" = fitten, "fixed" = feste W/K (Heizbetrieb)
(def fixed-wk   (double (or (:fixed_wk opts) 450.0))) ;; W/K, wenn model_mode=fixed
(def frost-set  (double (or (:frost_setpoint opts) 5.0)))    ;; Innen-Sollwert fuer kW-Auslegung
(def design-out (double (or (:design_outdoor opts) -15.0)))  ;; Auslegungs-Aussentemperatur

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
               "&past_days=" (min 92 history-days) "&forecast_days=1&timezone=UTC")
        now-h (-> (java.time.Instant/now) (.atZone java.time.ZoneOffset/UTC)
                  (.truncatedTo java.time.temporal.ChronoUnit/HOURS) .toEpochSecond)]
    ;; NUR bis zur aktuellen Stunde behalten. Open-Meteo liefert mit forecast_days=1 den REST des
    ;; heutigen Tages als Forecast mit - der darf NICHT in die "gemessene" Aussenkurve (sonst endet
    ;; sie in der Nacht/naechsten Frueh statt jetzt). Die Zukunft kommt aus der separaten Prognose.
    (into {} (filter (fn [[ts _]] (when-let [e (hour->epoch ts)] (<= e now-h)))
                     (or (parse-hourly (:hourly (om-get u))) {})))))

;; ---------- Psychrometrie (Magnus) ----------
(defn abs-hum [t rh]   ;; absolute Feuchte [g/m3]
  (when (and t rh)
    (/ (* 216.7 (/ (double rh) 100.0) 6.112 (Math/exp (/ (* 17.62 t) (+ 243.12 t)))) (+ 273.15 t))))
(defn dew-point [t rh] ;; Taupunkt [C]
  (when (and t rh (pos? (double rh)))
    (let [g (+ (/ (* 17.62 t) (+ 243.12 t)) (Math/log (/ (double rh) 100.0)))]
      (/ (* 243.12 g) (- 17.62 g)))))

(defn pearson [xs ys]  ;; Korrelationskoeffizient, nil bei zu wenig/konstant
  (let [n (count xs)]
    (when (> n 8)
      (let [mx (/ (reduce + xs) n) my (/ (reduce + ys) n)
            cov (reduce + (map (fn [x y] (* (- x mx) (- y my))) xs ys))
            sx (Math/sqrt (reduce + (map #(let [d (- % mx)] (* d d)) xs)))
            sy (Math/sqrt (reduce + (map #(let [d (- % my)] (* d d)) ys)))]
        (when (and (pos? sx) (pos? sy)) (/ cov (* sx sy)))))))

(defn solve-lin [A b]  ;; Gauss mit Teilpivot
  (let [k (count A)
        M0 (mapv (fn [row bi] (conj (vec (map double row)) (double bi))) A b)
        M (loop [M M0 i 0]
            (if (= i k) M
                (let [p (apply max-key (fn [r] (Math/abs (double (get-in M [r i])))) (range i k))
                      M (if (= p i) M (-> M (assoc i (M p)) (assoc p (M i))))
                      pv (double ((M i) i))
                      M (reduce (fn [M r] (let [f (/ (double ((M r) i)) pv)]
                                            (assoc M r (mapv #(- (double %1) (* f (double %2))) (M r) (M i)))))
                                M (range (inc i) k))]
                  (recur M (inc i)))))]
    (reduce (fn [x r] (let [row (M r) s (reduce + 0.0 (map (fn [j] (* (double (row j)) (x j))) (range (inc r) k)))]
                        (assoc x r (/ (- (double (row k)) s) (double (row r))))))
            (vec (repeat k 0.0)) (range (dec k) -1 -1))))

(defn harmonic-fit
  "Tages-Harmonische per Regression: y = c0+c1 t+c2 t^2 + A cos(wt)+B sin(wt).
   Liefert {:amp sqrt(A^2+B^2) :phase Stunde-des-Maximums}. Kein willkuerlicher Detrend."
  [ser]
  (when (and (sequential? ser) (> (count ser) 30) (every? some? ser))
    (let [n (count ser) w (/ (* 2 Math/PI) 24.0)
          X (mapv (fn [kk] [1.0 (double kk) (* (double kk) (double kk)) (Math/cos (* w kk)) (Math/sin (* w kk))]) (range n))
          xtx (vec (for [i (range 5)] (vec (for [j (range 5)] (reduce + (map (fn [r] (* (nth r i) (nth r j))) X))))))
          xty (vec (for [i (range 5)] (reduce + (map (fn [r y] (* (nth r i) y)) X ser))))
          beta (solve-lin xtx xty)
          A (nth beta 3) B (nth beta 4)]
      {:amp (Math/sqrt (+ (* A A) (* B B))) :phase (mod (/ (Math/atan2 B A) w) 24.0)})))

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

(defn ffill-to-now
  "Schreibt den letzten bekannten Stundenwert bis zur aktuellen Stunde fort. Begruendung: Der Shelly
   sendet nur bei 0.1-Grad-Aenderung, im traegen leeren Haus also stundenlang nichts - der Sensor
   ist aber aktiv und eine unveraenderte Temperatur heisst, der letzte Wert IST der aktuelle. So
   entsteht stuendlich ein Datenpunkt (wie beim History-Raster). Sicherheitslimit `max-h`, damit ein
   wirklich toter Sensor keine tagelange erfundene Flachlinie erzeugt."
  [m max-h]
  (if (empty? m) m
      (let [last-key (apply max-key #(or (hour->epoch %) 0) (keys m))
            last-e (hour->epoch last-key)
            last-v (get m last-key)
            now-e (-> (java.time.Instant/now) (.atZone java.time.ZoneOffset/UTC)
                      (.truncatedTo java.time.temporal.ChronoUnit/HOURS) .toEpochSecond)
            cap-e (min now-e (+ last-e (* max-h 3600)))]
        (loop [e (+ last-e 3600) acc m]
          (if (> e cap-e) acc
              (recur (+ e 3600)
                     (assoc acc (.format (-> (java.time.Instant/ofEpochSecond e)
                                             (.atZone java.time.ZoneOffset/UTC)) hour-fmt)
                            last-v)))))))

(defn build-archive []
  (let [in (or (indoor-hourly) {})
        ;; Innen-Sensor ist sparse (nur bei Aenderung) -> letzten Wert stuendlich bis jetzt fortschreiben.
        t-in (ffill-to-now (:t_in in) 24) rh-in (ffill-to-now (:rh_in in) 24)
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
  "Reines Leitungs-RC ueber Stundenraster: dT/dt = a*(Tout - T). Nur a (tau) per Grid-Suche,
   KEIN konstanter Term g (der machte die Prognose unphysikalisch/fensterabhaengig).
   tau=1/a. W/K = C/tau. Rueckwaerts-Projektion + Vorwaerts-Prognose beide physikalisch beschraenkt."
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
                  rh-in-pts (->> rows (filter :rh_in) (map (fn [r] [(:e r) (:rh_in r)])) (sort-by first) vec)
                  rhin (when (>= (count rh-in-pts) 12) (mapv #(interp-at rh-in-pts %) hrs))
                  rhout (mapv #(get-in om [% :rh_out]) hrs)
                  mean (/ (reduce + tin) n)
                  sst (reduce + (map #(let [d (- % mean)] (* d d)) tin))
                  ;; REINES Leitungsmodell: dT/dt = a*(Tout - T). Nur a (tau) gefittet, KEIN b/g.
                  ;; Physikalisch beschraenkt: Innen laeuft immer zur Aussentemperatur, nie darunter/darueber.
                  eval-a (fn [a]
                           (let [al (- 1.0 a)]
                             (loop [k 0 tb (double (first tin)) sse 0.0]
                               (if (= k n) {:a a :sse sse}
                                   (let [e (- (nth tin k) tb)]
                                     (recur (inc k) (+ (* al tb) (* a (nth tout k))) (+ sse (* e e))))))))
                  grid (map #(Math/exp %)
                            (map #(+ (Math/log 0.001) (* % (/ (- (Math/log 0.06) (Math/log 0.001)) 100.0))) (range 101)))
                  ;; a: aus Messung fitten (auto) ODER fest aus config (fixed) - im Heizbetrieb geht der
                  ;; Freilauf-Fit nicht, dann feste W/K: a = dt/tau = dt*UA/C (Euler, pro Stunde).
                  {:keys [a sse]} (if (= model-mode "fixed")
                                    (eval-a (/ (* 3600.0 fixed-wk) (* c-mj-estimate 1e6)))
                                    (apply min-key :sse (map eval-a grid)))
                  r2 (if (pos? sst) (- 1.0 (/ sse sst)) 0.0)
                  ;; Gezeichnete Modell-Kurve = Vorwaerts-Freilauf ab erster Messung. Das ist GENAU die
                  ;; Trajektorie, die der Fit minimiert, und numerisch stabil (abklingend). Die frueher
                  ;; gezeigte Rueckwaerts-Integration verstaerkt den Fehler je Schritt um 1/(1-a) -> lief
                  ;; ueber 14 Tage auseinander (gemessen: RMSE 0.66 statt 0.16 vorwaerts).
                  fwd (vec (reductions (fn [t k] (+ (* (- 1.0 a) t) (* a (nth tout k))))
                                       (double (first tin)) (range (dec n))))
                  tau-h (/ 1.0 a) tau-d (/ tau-h 24.0)
                  wk (/ (* c-mj-estimate 1e6) (* tau-h 3600.0))   ;; W/K = C / tau
                  daily-amp (fn [ser] (let [ds (partition-all 24 ser)]
                                        (/ (reduce + (map #(- (apply max %) (apply min %)) ds)) (count ds))))
                  a-in (daily-amp tin) a-out (daily-amp tout)
                  wday (/ (* 2 Math/PI) 24.0)
                  damp (/ a-out (max 0.05 a-in))
                  tau-daily (when (> damp 1.0) (/ (Math/sqrt (- (* damp damp) 1.0)) wday 24.0))
                  mi (/ (reduce + tin) n) mo (/ (reduce + tout) n)
                  si (Math/sqrt (reduce + (map #(let [d (- % mi)] (* d d)) tin)))
                  so (Math/sqrt (reduce + (map #(let [d (- % mo)] (* d d)) tout)))
                  xcorr (vec (for [lag (range 0 25)]
                               (let [ps (map (fn [k] [(nth tout (- k lag)) (nth tin k)]) (range lag n))
                                     cov (reduce + (map (fn [[o i]] (* (- o mo) (- i mi))) ps))]
                                 {:lag lag :r (if (and (pos? si) (pos? so)) (/ cov (* si so)) 0.0)})))
                  ;; Autokorrelation (ACF): corr(T(t), T(t+lag)) ueber den Verzug
                  acf (fn [ser]
                        (let [m (/ (reduce + ser) (count ser))
                              dev (mapv #(- % m) ser)
                              denom (reduce + (map #(* % %) dev))
                              maxlag (min 120 (quot (count ser) 2))]
                          (vec (for [L (range 0 (inc maxlag))]
                                 {:lag L :r (if (pos? denom)
                                              (/ (reduce + (map * dev (drop L dev))) denom) 0.0)}))))
                  acf-in (acf tin) acf-out (acf tout)
                  ;; Tages-Analyse (harmonische Regression, 24 h)
                  h-tin (harmonic-fit tin) h-tout (harmonic-fit tout)
                  h-rhin (harmonic-fit rhin) h-rhout (harmonic-fit rhout)
                  daily {:t_in h-tin :t_out h-tout :rh_in h-rhin :rh_out h-rhout
                         :t_damp (when (and h-tin h-tout (pos? (:amp h-tin))) (/ (:amp h-tout) (:amp h-tin)))
                         :t_lag  (when (and h-tin h-tout) (mod (- (:phase h-tin) (:phase h-tout)) 24.0))
                         :rh_damp (when (and h-rhin h-rhout (pos? (:amp h-rhin))) (/ (:amp h-rhout) (:amp h-rhin)))}]
              {:tau-d tau-d :r2 r2 :wk wk :c-mj c-mj-estimate :a a
               :tau-daily tau-daily :damp damp :a-in a-in :a-out a-out :xcorr xcorr
               :acf-in acf-in :acf-out acf-out :daily daily
               :model (zipmap hrs fwd)})))))
    (catch Exception e (println "[aqua] thermal-model Fehler:" (.getMessage e)) nil)))

(defn forecast-outdoor []
  (let [u (str "https://api.open-meteo.com/v1/forecast?latitude=" lat "&longitude=" lon
               "&hourly=temperature_2m,shortwave_radiation&past_days=0&forecast_days=7&timezone=UTC")
        h (:hourly (om-get u))]
    (when h
      (->> (map (fn [ti te ra] (when (some? te) {:e (hour->epoch ti) :t_out te :solar (or ra 0.0)}))
                (:time h) (:temperature_2m h) (:shortwave_radiation h))
           (remove nil?) (sort-by :e) vec))))

(defn forecast-series
  "RC vorwaerts ab dem letzten INNEN-Messwert: erst ueber die gemessene Aussentemp der
   (evtl. mehrstuendigen) Sensor-Luecke, dann ueber den Open-Meteo-Forecast. Erster Punkt liegt
   eine Stunde nach dem letzten Messwert und wird aus genau diesem integriert -> lueckenloser,
   stundengenauer Anschluss, kein Sprung. Die Anzeige wird passend bis e0 beschnitten (build-response)."
  [tm arch]
  (when (and tm (:a tm))
    (try
      (let [a (:a tm)
            rows (archive->rows arch)
            last-in (last (filter :t_in rows))]
        (when last-in
          (let [e0 (:e last-in) t0 (double (:t_in last-in))
                emax (:e (last rows))
                ;; gemessene Aussentemp NACH dem letzten Innen-Messwert (Bruecke ueber die Sensor-Luecke)
                gap (->> rows (filter #(and (> (:e %) e0) (some? (:t_out %))))
                         (map (fn [r] {:e (:e r) :t_out (:t_out r)})))
                ;; danach der echte Forecast (Stunden nach dem Archivende)
                fut (filter #(> (:e %) emax) (or (forecast-outdoor) []))
                timeline (take 160 (concat gap fut))]
            (when (seq timeline)
              (loop [fs timeline tprev t0 acc []]
                (if (empty? fs) acc   ;; vorwaerts (aufsteigende Zeit)
                    (let [f (first fs)
                          tn (+ tprev (* a (- (:t_out f) tprev)))]   ;; rein: dT/dt = a*(Tout - T)
                      (recur (rest fs) tn (conj acc {:e (:e f) :t_in tn :t_out (:t_out f)})))))))))
      (catch Exception e (println "[aqua] forecast Fehler:" (.getMessage e)) nil))))

;; ---------- State / Loop ----------
(def state (atom {:status "startet..." :archive {} :model nil :series nil}))

(defn refresh! []
  (println "[aqua] Archiv-Update laeuft...")
  (let [arch (build-archive)
        tm (thermal-model arch)
        ;; Wetter-Lueckenpruefung: fehlen Stundenwerte (t_out) im erwarteten Fenster bis JETZT?
        now-e (-> (java.time.Instant/now) (.atZone java.time.ZoneOffset/UTC)
                  (.truncatedTo java.time.temporal.ChronoUnit/HOURS) .toEpochSecond)
        have (into #{} (keep (fn [[ts v]] (when (some? (:t_out v)) (hour->epoch ts))) arch))
        missing (->> (range (- now-e (* history-days 86400)) (inc now-e) 3600) (remove have) sort)]
    (reset! state {:status "ok" :archive arch :updated (str (java.time.OffsetDateTime/now))})
    (when (seq missing)
      (println (format "[aqua] WARN Wetter-Luecken: %d fehlende Stundenwerte, neueste fehlende %s"
                       (count missing)
                       (subs (str (java.time.Instant/ofEpochSecond (last missing))) 0 16))))
    (println (str "[aqua] fertig: " (count arch) " Std Archiv"
                  (when tm (format "; tau=%.1f d, W/K=%.0f, R2=%.3f" (:tau-d tm) (:wk tm) (:r2 tm)))))))

(defn densify-field
  "Fuellt `field` (z.B. :t_in) fuer JEDE Stunde in `rows`: linear zwischen den bekannten Messpunkten,
   nach der letzten Messung Wert halten (Sensor aktiv, Temp unveraendert) bis max `hold-h` Stunden,
   VOR der ersten Messung nichts erfinden. Ergebnis: jede Stunde hat einen Wert (kein Loch mehr)."
  [rows field hold-h]
  (let [known (->> rows (keep (fn [r] (when (some? (field r)) [(:e r) (double (field r))])))
                   (sort-by first) vec)]
    (if (< (count known) 2) rows
        (let [e0 (ffirst known) eN (first (peek known)) vN (second (peek known))
              cap (+ eN (* hold-h 3600))]
          (mapv (fn [r]
                  (let [e (:e r)]
                    (cond (some? (field r)) r                      ;; echte Messung bleibt
                          (< e e0) r                               ;; vor erster Messung: nichts erfinden
                          (<= e eN) (assoc r field (interp-at known e))  ;; dazwischen: interpolieren
                          (<= e cap) (assoc r field vN)            ;; nach letzter: halten (bis hold-h)
                          :else r)))
                rows)))))

;; ---------- Web ----------
(defn parse-days [qs]
  (when qs (when-let [m (re-find #"days=(\d+)" qs)] (Integer/parseInt (second m)))))

(defn build-response
  "Antwort fuer /api/data, optional auf die letzten `days` Tage gefenstert
   (Fenster <= ~9 d schneidet das Umzugs-Rauschen weg). Fit wird pro Fenster gerechnet."
  [days]
  (let [arch0 (:archive @state)
        now-h (-> (java.time.Instant/now) (.atZone java.time.ZoneOffset/UTC)
                  (.truncatedTo java.time.temporal.ChronoUnit/HOURS) .toEpochSecond)
        ;; Zukunfts-Stunden (frueher persistierter Forecast) aus der MESSANZEIGE werfen -> Anzeige/Karte
        ;; enden bei der aktuellen Stunde, nicht beim 23:00-Nacht-Forecast. Zukunft nur via Prognose.
        arch (into {} (filter (fn [[ts _]] (when-let [e (hour->epoch ts)] (<= e now-h))) arch0))
        rows-all (archive->rows arch)
        maxe (when (seq rows-all) (apply max (map :e rows-all)))
        cutoff (when (and days (pos? days) maxe) (- maxe (* days 86400)))
        rows (if cutoff (filterv #(>= (:e %) cutoff) rows-all) rows-all)
        warch (if cutoff (into {} (filter #(when-let [e (hour->epoch (key %))] (>= e cutoff)) arch)) arch)
        ;; VOLLES Archiv zeigen (Aussen bis jetzt) - NICHT mehr auf den letzten Innen-Messwert
        ;; beschneiden. Der Innen-Sensor ist sparse; die Prognose/Nowcast-Linie ueberbrueckt die
        ;; Luecke [letzter Innen-Messwert .. jetzt] im Frontend als Overlay (kein Anzeige-Verlust).
        ;; JEDE Stunde einen Wert: sparse Innen-Messungen verdichten + Aussen-Luecken interpolieren.
        s (-> (attach-model rows nil)
              (densify-field :t_in 24) (densify-field :rh_in 24)
              (densify-field :t_out 3) (densify-field :rh_out 3))
        in-eps (->> rows (filter :t_in) (map :e) sort)
        n-in (count in-eps)
        tm (thermal-model warch)
        fc (forecast-series tm warch)
        span-d (when (>= n-in 2) (/ (- (last in-eps) (first in-eps)) 86400.0))
        ;; Korrelationen (nur Stunden mit beiden Werten)
        q-of (fn [r] (abs-hum (:t_in r) (:rh_in r)))
        pr (fn [fx fy] (let [ps (keep (fn [r] (let [x (fx r) y (fy r)]
                                                (when (and (some? x) (some? y)) [x y]))) s)]
                         (when (seq ps) (pearson (mapv first ps) (mapv second ps)))))
        corr {:t_rh (pr :t_in :rh_in) :t_q (pr :t_in q-of) :tout_tin (pr :t_out :t_in)
              :rh_in_out (pr :rh_in :rh_out)}
        ;; Klima-Stil: pro Kanal ALLE einzelnen Tageskurven (24 h, ECHTE Messwerte), uebereinandergelegt.
        ;; Trend inkl. Innerhalb-Tag-Slope RAUS per HOCHPASS: zentrierten 24h-Mittel je Punkt abziehen.
        ;; 24 h = eine volle Tagesperiode -> das 24h-Mittel des Tageszyklus ist 0, der Zyklus bleibt also
        ;; erhalten, nur die langsame Drift (und ihr Slope im Tag) verschwindet -> Kurven kippen nicht mehr.
        ;; (36 h waere falsch: kein Vielfaches der Periode -> wuerde den Zyklus teils mit wegmitteln.)
        daily-of (fn [field]
                   (let [pts (->> s (filter #(some? (field %))) (sort-by :e) vec)
                         n (count pts) vals (mapv field pts)
                         anom (mapv (fn [i] (let [lo (max 0 (- i 12)) hi (min (dec n) (+ i 12))
                                                  w (subvec vals lo (inc hi))]
                                              (- (nth vals i) (/ (reduce + w) (double (count w)))))) (range n))
                         by-day (group-by (fn [i] (quot (:e (nth pts i)) 86400)) (range n))]
                     (->> (sort (keys by-day))
                          (keep (fn [d]
                                  (let [is (by-day d)
                                        byh (into {} (map (fn [i] [(mod (quot (:e (nth pts i)) 3600) 24) (nth anom i)]) is))]
                                    (when (>= (count byh) 20)   ;; nur weitgehend volle Tage
                                      (mapv (fn [h] (byh h)) (range 24))))))
                          vec)))
        daily-curves {:t_in (daily-of :t_in) :t_out (daily-of :t_out)
                      :rh_in (daily-of :rh_in) :rh_out (daily-of :rh_out)}]
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
       :t_model (when tm (mapv #(get (:model tm) (:e %)) s))
       :rh_in (mapv :rh_in s)
       :rh_out (mapv :rh_out s)
       :thermal (when tm {:tau_d (:tau-d tm) :r2 (:r2 tm) :wk (:wk tm) :c_mj (:c-mj tm)
                          :tau_daily (:tau-daily tm) :damp (:damp tm)
                          :a_in (:a-in tm) :a_out (:a-out tm) :xcorr (:xcorr tm)
                          :acf_in (:acf-in tm) :acf_out (:acf-out tm) :daily (:daily tm)
                          :mode model-mode :frost_set frost-set :design_out design-out
                          ;; Auslegungs-Waermeleistung = UA * (Sollwert - Auslegungs-Aussentemp)
                          :kw_design (/ (* (:wk tm) (- frost-set design-out)) 1000.0)})
       :corr corr
       :forecast (when (seq fc) {:time (mapv :e fc) :t_in (mapv :t_in fc) :t_out (mapv :t_out fc)})
       :indoor_hours n-in :indoor_span_days span-d :days days
       :updated (:updated @state) :status (:status @state)
       :daily_curves daily-curves
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

;; MIT config.yaml version synchron halten! Das Log druckt sie -> Update-Landung ist beweisbar.
(def build-version "0.11.16")

(defn -main [& _]
  (http/run-server handler {:port port :ip "0.0.0.0"})
  (println (str "[aqua] BUILD " build-version " auf 0.0.0.0:" port " - Sensor " sensor
                " @ " lat "," lon " - Token " (if (str/blank? (str token)) "FEHLT" "ok")))
  (future
    (loop []
      (try (refresh!) (catch Exception e (println "[aqua] refresh-Fehler" (.getMessage e))))
      (Thread/sleep (* 60 60 1000)) ;; stuendlich
      (recur)))
  @(promise))

(-main)
