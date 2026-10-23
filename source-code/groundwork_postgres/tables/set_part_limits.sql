-- A plan part's time limits: for the transaction (:local true, what SET LOCAL does) or the session.
SELECT set_config('statement_timeout', :statement-timeout, :local) AS statement_timeout,
       set_config('lock_timeout', :lock-timeout, :local) AS lock_timeout
