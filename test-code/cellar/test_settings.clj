(ns cellar.test-settings
  "The settings and options the tests run with, each written out, and a
   column with its five options: the tests' own shorthand, not cellar's."
  (:require
   [cellar.api :as cellar]))

(def port (some-> (System/getenv "CELLAR_TEST_PORT") parse-long))

(defn make-pool-settings
  "A test Postgres on localhost, the user from CELLAR_TEST_USER, no password, no TLS."
  [pool-size statement-timeout-ms]
  {:host                           "localhost"
   :port                           port
   :database                       "postgres"
   :user                           (System/getenv "CELLAR_TEST_USER")
   :password                       nil
   :tls                            :off
   :tls-root-cert                  nil
   :pool-size                      pool-size
   :connection-wait-ms             30000
   :connection-check-wait-ms       5000
   :connection-max-lifetime-ms     1800000
   :connection-keepalive-every-ms  120000
   :leak-warning-after-ms          :none
   :connect-timeout-seconds        10
   :read-timeout-seconds           :none
   :tcp-keepalive?                 false
   :statement-timeout-ms           statement-timeout-ms
   :lock-timeout-ms                :none
   :idle-in-transaction-timeout-ms :none
   :time-zone                      "UTC"})

(defn start-pool! [pool-size statement-timeout-ms]
  (cellar/start-pool! (make-pool-settings pool-size statement-timeout-ms)))

(def migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}})

(defn column
  "[k type options], the options none but what is given."
  [k type & {:as given}]
  [k type (merge {:primary-key? false :optional? false :unique? false :default nil :references nil} given)])
