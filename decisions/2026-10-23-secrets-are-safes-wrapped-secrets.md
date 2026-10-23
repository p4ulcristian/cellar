# Secrets are safe's wrapped secrets

> Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (`:password` is `[:maybe safe/secret]`, no longer `[:maybe :string]`). Since v0.4.3, on [safe](https://github.com/p4ulcristian/safe) v0.1.0.

**Decision:** the pool's `:password` is a secret of safe's, `[:password [:maybe safe/secret]]`: in the app's settings file `#secret :cellar/password`, read by `safe/load`; nil, written out, for none. A password written out as text is refused, `:cellar/bad-settings` with `{:password ["should be a #secret, read from the settings' secrets source"]}`, and the refusal never holds the text. cellar calls `(safe/reveal password)` in one place, where it hands the password to HikariCP (`cellar.queries.postgres`); a migration's owner pool is a pool like any other and takes its password the same way. The tests make theirs with `safe/make-secret`, and their throwaway Postgres asks for it (scram-sha-256), so the revealed text is what connects.

**Why:** a settings map is printed, logged and put in ex-data; a password in it as a string leaks by every one of those. Wrapped, it prints as `#secret :cellar/password` everywhere but the one call that hands it to the driver, which can be searched for. safe's [A secret stays wrapped until it is used](https://github.com/p4ulcristian/safe/blob/main/decisions/2026-10-23-a-secret-stays-wrapped-until-it-is-used.md) says how.

**Instead of:**
- a string, as before: every log of the settings is a leak
- taking either a string or a secret: the string would be the easy one to write, and its leak would come back
- revealing in the settings check: the text would then travel through the pool's code as a string
