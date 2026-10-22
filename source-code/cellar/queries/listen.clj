(ns cellar.queries.listen
  "Hearing Postgres's NOTIFY: one connection of the pool held open with
   LISTEN, read on a virtual thread. Notifications that arrive close
   together (within 50 ms) are handed on as one batch. When the connection
   breaks, the thread opens another, LISTENs again and calls on-reconnect:
   whatever was sent in between was missed."
  (:require
   [next.jdbc :as jdbc])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection)
   (org.postgresql PGConnection PGNotification)))

(def ^:private ^System$Logger logger (System/getLogger "cellar"))

(defn- read-batch [^PGConnection connection]
  (when-let [first-ones (seq (.getNotifications connection 500))]
    (Thread/sleep 50)
    (for [^PGNotification notification (concat first-ones (.getNotifications connection 1))]
      {:channel (.getName notification) :payload (.getParameter notification)})))

(defn- hand-on! [f & arguments]
  (try (apply f arguments)
       (catch Exception e
         (.log logger System$Logger$Level/ERROR "A listener's function failed" ^Throwable e))))

(defn- listen-on-one-connection! [pool channel running? again? {:keys [on-notifications on-reconnect]}]
  (with-open [^Connection connection (jdbc/get-connection (:cellar/datasource pool))]
    (.setAutoCommit connection true)
    (with-open [statement (.createStatement connection)]
      (.execute statement (str "LISTEN " channel)))
    ;; told only once listening again, so nothing falls between
    (when again? (hand-on! on-reconnect))
    (let [pg (.unwrap connection PGConnection)]
      (while @running?
        (when-let [batch (seq (read-batch pg))]
          (hand-on! on-notifications (vec batch)))))))

(defn start-listening!
  "LISTENs on a channel (a plain Postgres name). (on-notifications
   [{:channel :payload} …]) for each batch, (on-reconnect) after a break.
   Holds one connection of the pool for as long as it runs."
  [pool channel handlers]
  (when-not (re-matches #"[a-z_][a-z0-9_]*" channel)
    (throw (ex-info (str "Not a channel name: " channel) {:type :cellar/bad-channel :channel channel})))
  (let [running? (atom true)
        thread   (Thread/startVirtualThread
                  (fn []
                    (loop [again? false]
                      (when @running?
                        (try (listen-on-one-connection! pool channel running? again? handlers)
                             (catch Exception e
                               (when @running?
                                 (.log logger System$Logger$Level/WARNING (str "Listening on " channel " broke; again in 1 s") ^Throwable e)
                                 (Thread/sleep 1000))))
                        (recur true)))))]
    {:running? running? :thread thread}))

(defn stop-listening!
  "Stops listening; the held connection goes back to the pool within a second."
  [{:keys [running? ^Thread thread]}]
  (reset! running? false)
  (.join thread 2000))
