(ns cellar.queries.pool-lines
  "HikariCP's and PgJDBC's own lines, written to the log of the pool they
   are about, as :cellar/pool-said.

   HikariCP writes through SLF4J and PgJDBC through java.util.logging, each
   to loggers the whole JVM shares. cellar brings slf4j-jdk14, so SLF4J
   hands HikariCP's lines to java.util.logging too, and puts one handler
   on two java.util.logging loggers only, com.zaxxer.hikari and
   org.postgresql (info and up, not on to the root's handlers); every
   other logger is left as it was.

   The handler finds a line's pool by its name, which start-pool! makes
   unique in the JVM (cellar-<database>-<n>), in this order:
   1. the line starts with it, \"cellar-shop-2 - Start completed.\"
      (HikariCP's lines about a pool do);
   2. the thread is the pool's own, \"cellar-shop-2:housekeeper\"
      (HikariCP's threads: a leak warning, a connection opened or closed);
   3. cellar is working with the pool on this thread: start-pool!,
      stop-pool!, or a connection cellar holds (a leaked connection given
      back, PgJDBC opening one).
   A line no pool owns goes to each log of the open pools, :cellar/pool
   nil; with none open, to java.util.logging's root, as before."
  (:import
   (java.util.logging Handler Level LogRecord Logger SimpleFormatter)
   (org.slf4j LoggerFactory)))

(defonce ^:private open-pools
  ;; pool name -> the log start-pool! was given
  (atom {}))

(defonce ^:private pools-started (atom 0))

(defonce ^:private ^ThreadLocal pool-on-thread (ThreadLocal.))

(def ^:private ^SimpleFormatter formatter (SimpleFormatter.))

(defn make-pool-name
  "A pool name no other pool in this JVM has: cellar-<database>-<n>."
  [database]
  (str "cellar-" database "-" (swap! pools-started inc)))

(defn- find-pool-name [^String message ^String thread-name pools]
  (or (some (fn [pool-name] (when (.startsWith message (str pool-name " - ")) pool-name)) (keys pools))
      (some (fn [pool-name] (when (.startsWith thread-name (str pool-name ":")) pool-name)) (keys pools))
      (let [on-thread (.get pool-on-thread)]
        (when (contains? pools on-thread) on-thread))))

(defn- turn-level-into-keyword [^Level level]
  (let [value (.intValue level)]
    (cond (>= value (.intValue Level/SEVERE))  :error
          (>= value (.intValue Level/WARNING)) :warn
          (>= value (.intValue Level/INFO))    :info
          :else                                :debug)))

(defn- write-line! [^LogRecord record]
  (let [pools     @open-pools
        message   (str (.formatMessage formatter record))
        pool-name (find-pool-name message (.getName (Thread/currentThread)) pools)
        logs      (if pool-name [(get pools pool-name)] (distinct (vals pools)))
        data      (cond-> {:cellar/pool    pool-name
                           :cellar/logger  (.getLoggerName record)
                           :cellar/message message}
                    (.getThrown record) (assoc :error (.getThrown record)))]
    (if (seq logs)
      (doseq [log logs]
        (try (log (turn-level-into-keyword (.getLevel record)) :cellar/pool-said data)
             (catch Throwable e
               ;; a log never throws (logbook's); one that does is said here, not to HikariCP
               (.println System/err (str "cellar: a log threw on " message ": " e)))))
      (.log (Logger/getLogger "") record))))

(defonce ^:private loggers
  ;; held here: java.util.logging keeps its loggers weakly, and a logger
  ;; collected would come back without the handler
  (delay
    (let [handler (proxy [Handler] []
                    (publish [record] (write-line! record))
                    (flush [])
                    (close []))]
      (mapv (fn [^String logger-name]
              (doto (Logger/getLogger logger-name)
                (.setLevel Level/INFO)
                (.setUseParentHandlers false)
                (.addHandler handler)))
            ["com.zaxxer.hikari" "org.postgresql"]))))

(defn call-with-pool-on-thread
  "(f), with this thread's HikariCP and PgJDBC lines meanwhile the pool's."
  [pool-name f]
  (let [before (.get pool-on-thread)]
    (.set pool-on-thread pool-name)
    (try (f)
         (finally (.set pool-on-thread before)))))

(defn add-pool!
  "From now on the pool's lines go to log. Says, on log, when SLF4J hands
   HikariCP's lines to a binding other than slf4j-jdk14 (the app has one of
   its own): those lines don't reach the log."
  [pool-name log]
  (force loggers)
  (swap! open-pools assoc pool-name log)
  (let [factory-name (.getName (class (LoggerFactory/getILoggerFactory)))]
    (when-not (= "org.slf4j.jul.JDK14LoggerFactory" factory-name)
      (log :warn :cellar/pool-lines-elsewhere {:cellar/pool                 pool-name
                                               :cellar/slf4j-logger-factory factory-name}))))

(defn remove-pool!
  "The pool is closed; its lines, if any come, have no pool."
  [pool-name]
  (swap! open-pools dissoc pool-name))
