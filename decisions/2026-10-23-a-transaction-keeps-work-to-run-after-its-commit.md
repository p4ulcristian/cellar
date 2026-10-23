# A transaction keeps work to run after its commit; work that must happen is a job

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P7: 44 calls in 25 files run after a commit). Not built yet.

**What was wrong:** nothing could run after a commit. A handler runs inside doorman's transaction and can't wait for it; attic's `delete-file!` asks to be run "after the app's transaction is kept", which no caller could do.

**Decision:**

```clojure
(cellar/run-after-commit! transaction (fn [] (promotions/clear!)))
```

- cellar keeps a list of work per transaction (doorman's, backroom's, `run-as-system!`'s, the app's own: every transaction cellar runs). It runs, in order, on the same thread, once COMMIT has returned; never after a rollback. A nested call joins the outer transaction's list.
- A failure is written to the log with the transaction's context; the commit stands, and the rest of the list still runs.
- Handed a pool instead of a transaction, it refuses (there is no commit to wait for).

**What it is for:** work that may be lost: this server's memory (a cache), an outside call whose loss is harmless (expiring a checkout session, an analytics event), attic's file delete. **Work that must happen** (an email, a payment step) is a backroom job queued in the same transaction, which survives a deploy and is retried; Iron Rainbow audits its 44 calls by that rule.

**Why:** one place serves every transaction, and the line between "may be lost" and "must happen" is the same as Iron Rainbow's own ("work that must happen is a job").

**The price:** work in the list is lost if the process dies between the commit and the run.

**Instead of:**
- **everything a backroom job**: a cache clear would run on one server, not this one, and a quick outside call waits for a worker
- **a bridge step after doorman's transaction step**: only handlers would get it; jobs, webhooks and system runs wouldn't
