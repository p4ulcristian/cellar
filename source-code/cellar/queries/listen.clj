(ns cellar.queries.listen
  "Hearing Postgres's NOTIFY: one connection of the pool held open with
   LISTEN, read on a virtual thread. Notifications that arrive close
   together (within :batch-window-ms) are handed on as one batch. When the
   connection breaks, the thread waits :reconnect-after-ms, opens another,
   LISTENs again and calls on-reconnect: whatever was sent in between was
   missed. A break, and a failure of either function the app hands in, is
   written to the options' :log."
  (:require
   [cellar.settings :as settings]
   [next.jdbc :as jdbc])
  (:import
   (java.sql Connection)
   (org.postgresql PGConnection PGNotification)))

(defn- read-batch [^PGConnection connection {:keys [batch-window-ms check-stopped-every-ms]}]
  (when-let [first-ones (seq (.getNotifications connection (int check-stopped-every-ms)))]
    (when (pos? batch-window-ms) (Thread/sleep (long batch-window-ms)))
    ;; without a wait: what has arrived by now
    (for [^PGNotification notification (concat first-ones (.getNotifications connection))]
      {:channel (.getName notification) :payload (.getParameter notification)})))

(defn- hand-on! [{:keys [log]} channel f & arguments]
  (try (apply f arguments)
       (catch Exception e
         (log :error :cellar/listener-function-failed {:cellar/channel channel :error e}))))

(defn- listen-on-one-connection! [pool channel running? again? {:keys [on-notifications on-reconnect] :as options}]
  (with-open [^Connection connection (jdbc/get-connection (:cellar/datasource pool))]
    ;; NOTIFY is delivered outside a transaction only
    (.setAutoCommit connection true)
    (with-open [statement (.createStatement connection)]
      (.execute statement (str "LISTEN " channel)))
    ;; told only once listening again, so nothing falls between
    (when again? (hand-on! options channel on-reconnect))
    (let [pg (.unwrap connection PGConnection)]
      (while @running?
        (when-let [batch (seq (read-batch pg options))]
          (hand-on! options channel on-notifications (vec batch)))))))

(defn start-listening!
  "LISTENs on a channel (a plain Postgres name), as the options say
   (cellar.settings/listening-options-shape, every key required, checked
   first). Holds one connection of the pool for as long as it runs."
  [pool channel options]
  (settings/check-listening-options options)
  (when-not (re-matches #"[a-z_][a-z0-9_]*" channel)
    (throw (ex-info (str "Not a channel name: " channel) {:type :cellar/bad-channel :channel channel})))
  (let [{:keys [log reconnect-after-ms stop-wait-ms]} options
        running? (atom true)
        thread   (Thread/startVirtualThread
                  (fn []
                    (loop [again? false]
                      (when @running?
                        (try (listen-on-one-connection! pool channel running? again? options)
                             (catch Exception e
                               (when @running?
                                 (log :warn :cellar/listening-broke {:cellar/channel            channel
                                                                     :cellar/reconnect-after-ms reconnect-after-ms
                                                                     :error                     e})
                                 (Thread/sleep (long reconnect-after-ms)))))
                        (recur true)))))]
    {:running? running? :thread thread :stop-wait-ms stop-wait-ms}))

(defn stop-listening!
  "Stops listening: the thread ends within :check-stopped-every-ms (and the
   batch it is handing on), and its connection goes back to the pool.
   Waits at most :stop-wait-ms; returns whether the thread had ended."
  [{:keys [running? ^Thread thread stop-wait-ms]}]
  (reset! running? false)
  (when (pos? stop-wait-ms) (.join thread (long stop-wait-ms)))
  (not (.isAlive thread)))
