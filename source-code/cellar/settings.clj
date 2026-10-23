(ns cellar.settings
  "cellar's settings, and the data its functions take, as malli shapes,
   checked before anything runs. Nothing has a default: every key is
   written out (nil and :none where they are a choice), and a missing or
   unknown one refuses, named, with every other problem at once. A column
   is the one exception: it says only the options it has, an unsaid one
   meaning no or none.

   The pool's settings and the functions' options are closed maps. A table
   map is open: the libraries on top keep their own keys in it (doorbell's
   :doorbell), and cellar requires only its own. A column's options are
   cellar's alone, closed."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]
   [safe.api :as safe]))

;; -----------------------------------------------------------------------------
;; The pool

(def ^:private buckets-shape
  "A histogram's upper bounds; meter checks they are finite and go up."
  [:vector {:min 1} number?])

(def ^:private none-or-ms
  [:or [:= :none] [:int {:min 1}]])

(defn- fits-the-lifetime?
  ;; HikariCP turns off a keepalive that isn't shorter than the lifetime, and
  ;; a leak warning that is longer, each with only a log line: refused here
  [{:keys [connection-max-lifetime-ms connection-keepalive-every-ms leak-warning-after-ms]}]
  (or (= :none connection-max-lifetime-ms)
      (and (or (= :none connection-keepalive-every-ms) (< connection-keepalive-every-ms connection-max-lifetime-ms))
           (or (= :none leak-warning-after-ms) (<= leak-warning-after-ms connection-max-lifetime-ms)))))

(defn- has-root-cert-only-with-tls? [{:keys [tls tls-root-cert]}]
  (or (= :verified tls) (nil? tls-root-cert)))

(def settings-shape
  "What start-pool! takes, every key required.

   Where and who:
   :host :port :database :user, and :password, a secret (safe's, read
   from the app's secrets: #secret :cellar/password), nil for none.
   :tls: :verified (encrypted, the server's certificate checked, and its
   name must be :host) or :off. :tls-root-cert: with :verified, a file of
   the certificates to trust, or nil for the Java runtime's own; with
   :off, nil.

   The pool (HikariCP):
   :pool-size: how many connections, a fixed number.
   :connection-wait-ms: how long a query waits for a free connection before
   it fails (at least 250).
   :connection-check-wait-ms: how long the pool waits for a connection to
   answer when it checks it is alive (at least 250).
   :connection-max-lifetime-ms: a connection is closed and replaced after
   this long, when no query uses it (at least 30000), or :none.
   :connection-keepalive-every-ms: how often an idle connection is checked,
   so a firewall doesn't drop it (at least 30000, and shorter than the
   lifetime), or :none.
   :leak-warning-after-ms: a connection held this long by one piece of code
   is logged as a possible leak (at least 2000, and no longer than the
   lifetime), or :none.

   The network (PgJDBC):
   :connect-timeout-seconds: how long opening a connection may take.
   :read-timeout-seconds: how long a connection waits for Postgres to say
   anything before it gives up on it (longer than any statement may run),
   or :none.
   :tcp-keepalive?: whether the system's TCP keepalive runs on the
   connections.

   Each connection's session (Postgres), set as it opens; :none is 0, no
   limit, written so a limit on the database role can't apply behind the
   settings' back:
   :statement-timeout-ms: Postgres cancels a statement that runs longer.
   :lock-timeout-ms: Postgres cancels a statement that waits longer for a lock.
   :idle-in-transaction-timeout-ms: Postgres closes a connection whose
   transaction sits idle this long.
   :time-zone: the session's time zone, \"UTC\".

   The metrics (on the meter start-pool! is handed):
   :metric-buckets: each histogram's upper bounds, in seconds, ascending
   (meter checks them), no default: {:query-seconds […]}, the time a query
   takes; :connection-acquire-seconds, the wait for a connection;
   :connection-usage-seconds, how long one is held."
  [:and
   [:map {:closed true}
    [:host :string]
    [:port :int]
    [:database :string]
    [:user :string]
    [:password [:maybe safe/secret]]
    [:tls [:enum :verified :off]]
    [:tls-root-cert [:maybe :string]]
    [:pool-size [:int {:min 1}]]
    [:connection-wait-ms [:int {:min 250}]]
    [:connection-check-wait-ms [:int {:min 250}]]
    [:connection-max-lifetime-ms [:or [:= :none] [:int {:min 30000}]]]
    [:connection-keepalive-every-ms [:or [:= :none] [:int {:min 30000}]]]
    [:leak-warning-after-ms [:or [:= :none] [:int {:min 2000}]]]
    [:connect-timeout-seconds [:int {:min 1}]]
    [:read-timeout-seconds [:or [:= :none] [:int {:min 1}]]]
    [:tcp-keepalive? :boolean]
    [:statement-timeout-ms none-or-ms]
    [:lock-timeout-ms none-or-ms]
    [:idle-in-transaction-timeout-ms none-or-ms]
    [:time-zone :string]
    [:metric-buckets [:map {:closed true}
                      [:query-seconds buckets-shape]
                      [:connection-acquire-seconds buckets-shape]
                      [:connection-usage-seconds buckets-shape]]]]
   [:fn {:error/message "the keepalive must be shorter than the lifetime, and the leak warning no longer"}
    fits-the-lifetime?]
   [:fn {:error/message ":tls-root-cert is nil when :tls is :off"}
    has-root-cert-only-with-tls?]])

;; -----------------------------------------------------------------------------
;; The functions' options

(def log-entry
  "The app's log function, (log level event data), made by logbook: the
   functions that write a line take it under :log in their options; cellar
   never decides how a line is written."
  [:log [fn? {:error/message "should be the app's log function, (log level event data), made by logbook"}]])

(def meter-entry
  "The app's meter, made by meter's make-meter: the functions that count
   take it under :meter in their options and declare cellar's metrics on
   it. A value make-meter didn't make is refused by meter
   (:meter/not-a-meter)."
  [:meter [some? {:error/message "should be the app's meter, made by meter.api/make-meter"}]])

(def pool-options-shape
  "start-pool!'s options. :log: the app's log; HikariCP's and PgJDBC's own
   lines about the pool (its start and stop, a leak warning, a connection
   that failed) are written to it, as :cellar/pool-said. :meter: the app's
   meter, holding no other pool's metrics; the pool's are declared on it
   (cellar.queries.metrics)."
  [:map {:closed true}
   log-entry
   meter-entry])

(def transaction-options-shape
  "run-in-transaction!'s options. :read-only?: true, Postgres refuses every
   write in it."
  [:map {:closed true}
   [:read-only? :boolean]])

(def part-options-shape
  "run-instant-part!'s and run-slow-part!'s options: the time limits the
   part's statements run under, in place of the pool's. :none is no limit.
   :statement-timeout-ms: a statement that runs longer is cancelled (the
   slow part builds indexes over every row). :lock-timeout-ms: a statement
   that waits longer for its table's lock is cancelled, so an ALTER queued
   behind a long transaction doesn't hold every query to that table behind
   it."
  [:map {:closed true}
   [:statement-timeout-ms none-or-ms]
   [:lock-timeout-ms none-or-ms]])

(def migrate-options-shape
  "migrate!'s options: each part's, as run-instant-part! and run-slow-part!
   take them, and :log, the app's log, which each planned statement is
   written to (:cellar/planned)."
  [:map {:closed true}
   [:instant-part part-options-shape]
   [:slow-part part-options-shape]
   log-entry])

(def listening-options-shape
  "start-listening!'s options, every key required.
   :on-notifications: (f [{:channel :payload} …]) for each batch.
   :on-reconnect: (f) after the connection broke and listening began again:
   what was sent in between was missed.
   :batch-window-ms: after the first notification, how long to wait for
   more, handed on with it as one batch (0: what has arrived by then).
   :check-stopped-every-ms: how long one wait for a notification lasts
   before the thread looks whether it was stopped.
   :reconnect-after-ms: after a break, how long to wait before connecting again.
   :stop-wait-ms: how long stop-listening! waits for the thread to end.
   :log: the app's log; a break in listening (:cellar/listening-broke) and
   a failure of :on-notifications or :on-reconnect
   (:cellar/listener-function-failed) are written to it.
   :meter: the app's meter; listening again after a break is counted on it
   (cellar_listening_reconnects_total)."
  [:map {:closed true}
   log-entry
   meter-entry
   [:on-notifications fn?]
   [:on-reconnect fn?]
   [:batch-window-ms [:int {:min 0}]]
   [:check-stopped-every-ms [:int {:min 1}]]
   [:reconnect-after-ms [:int {:min 0}]]
   [:stop-wait-ms [:int {:min 0}]]])

(def tables-read-options-shape
  "run-and-list-tables-read's options. :log: the app's log, the running
   request's when there is one; a query whose tables Postgres's planner
   can't say is written to it (:cellar/tables-read-unknown)."
  [:map {:closed true}
   log-entry])

;; -----------------------------------------------------------------------------
;; Table maps

(def ^:private reference-action
  ;; what a reference does when the row it points at is deleted, or its
  ;; columns change: Postgres's CASCADE, SET NULL, NO ACTION, RESTRICT
  [:enum :cascade :set-null :refuse :restrict])

(def ^:private reference-keys
  [[:on-delete reference-action]
   [:on-update reference-action]
   [:checked-at [:enum :statement :commit]]])

(def reference-of-column-shape
  "A column's reference, every key written out: :to, another table's
   one-column primary key (:shop/id); :on-delete, what a delete of the row
   it points at does to this one, and :on-update, what a change of that
   row's key does: :cascade (deleted, or changed, with it), :set-null (set
   to null; only on an :optional? column), :refuse (NO ACTION: refused while
   this row points at it, checked when the reference is) or :restrict
   (refused at once, even when checked at commit); :checked-at, :statement
   (after each statement) or :commit (DEFERRABLE INITIALLY DEFERRED: when
   the transaction commits)."
  (into [:map {:closed true} [:to :qualified-keyword]] reference-keys))

(def reference-shape
  "A reference over several columns: :columns, the row's, in order;
   :to, the other table's, in the same order, its primary key among them;
   :on-delete, :on-update and :checked-at as a column's reference says
   them (reference-of-column-shape); :set-null only when every one of
   :columns is :optional?."
  (into [:map {:closed true}
         [:columns [:sequential {:min 2} :qualified-keyword]]
         [:to [:sequential {:min 2} :qualified-keyword]]]
        reference-keys))

(def column-options-shape
  "A column's options, only the ones it has; an unsaid one is no or none,
   the safe side: NOT NULL, not unique, not a primary key, no default, no
   reference. :primary-key? :optional? (NULL allowed) :unique?, true or
   false; :default, nil for none, or :random-uuid, :now, a number,
   true/false or text; :references, nil, or a map: another table's one-column
   primary key and what its reference does (reference-of-column-shape).
   Closed: an unknown key is refused."
  [:map {:closed true}
   [:primary-key? {:optional true} :boolean]
   [:optional? {:optional true} :boolean]
   [:unique? {:optional true} :boolean]
   [:default {:optional true} [:maybe [:or [:enum :random-uuid :now] number? boolean? :string]]]
   [:references {:optional true} [:maybe reference-of-column-shape]]])

(def column-shape
  "A column: [key type] or [key type options], the type in wire's words."
  [:multi {:dispatch #(when (vector? %) (count %))}
   [2 [:tuple :qualified-keyword :any]]
   [3 [:tuple :qualified-keyword :any column-options-shape]]
   [::malli/default [:fn {:error/message "a column is [key type] or [key type options]"} (constantly false)]]])

(def trigger-shape
  "A trigger a library (or the app) declares on its table: Postgres calls
   :function, made by whoever declares it, with :arguments (text, [] for
   none), :timing :before or :after the :events (a set of :insert :update
   :delete), :for-each :row or :statement. :update-of, with :update among
   the events: nil for a change of any column, or the columns whose change
   calls it."
  [:map {:closed true}
   [:function :string]
   [:arguments [:sequential :string]]
   [:timing [:enum :before :after]]
   [:events [:set {:min 1} [:enum :insert :update :delete]]]
   [:update-of [:maybe [:sequential {:min 1} :qualified-keyword]]]
   [:for-each [:enum :row :statement]]])

(def table-shape
  "A table map, every key cellar reads written out. :table, its key;
   :columns, [key type] or [key type options] each (column-shape); :indexes,
   [[key …] …], [] for none; :references, references over several columns
   (reference-shape), [] for none (a one-column one is the column's
   :references); :triggers, the triggers declared on it (trigger-shape),
   [] for none; :gates, nil for none (no row-level security), or a SQL
   condition for each operation, \"false\" for none allowed; :on-change,
   nil for none, or the function the change triggers call and its
   arguments. Open: another library's keys ride along."
  [:map
   [:table :keyword]
   [:columns [:sequential column-shape]]
   [:indexes [:sequential [:sequential :qualified-keyword]]]
   [:references [:sequential reference-shape]]
   [:triggers [:sequential trigger-shape]]
   [:gates [:maybe [:map {:closed true}
                    [:read :string]
                    [:insert :string]
                    [:update :string]
                    [:delete :string]]]]
   [:on-change [:maybe [:map {:closed true}
                        [:function :string]
                        [:arguments [:sequential :string]]]]]])

(def tables-shape
  "The table maps."
  [:sequential table-shape])

;; -----------------------------------------------------------------------------
;; Checking

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn check-settings [settings]
  (check-data-for-shape "The settings" :cellar/bad-settings settings-shape settings))

(defn check-pool-options [options]
  (check-data-for-shape "The options" :cellar/bad-options pool-options-shape options))

(defn check-transaction-options [options]
  (check-data-for-shape "The options" :cellar/bad-options transaction-options-shape options))

(defn check-part-options [options]
  (check-data-for-shape "The options" :cellar/bad-options part-options-shape options))

(defn check-migrate-options [options]
  (check-data-for-shape "The options" :cellar/bad-options migrate-options-shape options))

(defn check-listening-options [options]
  (check-data-for-shape "The options" :cellar/bad-options listening-options-shape options))

(defn check-tables-read-options [options]
  (check-data-for-shape "The options" :cellar/bad-options tables-read-options-shape options))

(defn check-tables [tables]
  (check-data-for-shape "The tables" :cellar/bad-tables tables-shape tables))
