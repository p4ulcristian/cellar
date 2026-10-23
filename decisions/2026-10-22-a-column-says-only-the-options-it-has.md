# A column says only the options it has; a table map still writes out its keys

> **Changed in part** by [A reference writes out what it does on delete, on update, and when it is checked](2026-10-23-a-reference-writes-out-what-it-does-on-delete-update-and-when-checked.md) (2026-10-23, on tidy-up): `:references` is a map that writes out what the reference does, `{:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}`, not the bare key that meant all three unsaid.

> Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): a column no longer writes out its five options. Since v0.1.3.

**Decision:** a column is `[key type]` or `[key type options]`, and its options map says only the options it has. An unsaid option means no, or none:

| unsaid | means |
|---|---|
| `:optional?` | NOT NULL |
| `:unique?` | not unique |
| `:primary-key?` | not in the primary key |
| `:default` | no default |
| `:references` | no reference |

```clojure
{:table     :orders
 :columns   [[:order/id         :uuid         {:primary-key? true :default :random-uuid}]
             [:order/shop-id    :uuid         {:references :shop/id}]
             [:order/status     [:enum "draft" "done"] {:default "draft"}]
             [:order/note       :string       {:optional? true}]
             [:order/total      :decimal]]
 :indexes   [[:order/shop-id]]
 :gates     nil
 :on-change nil}
```

The rest of v0.1.2 stays:

- **The options are still checked, closed**: an unknown key (`{:primary? true}`) is refused, named, `:cellar/bad-tables`, and so is a value of the wrong kind. A column that is neither `[key type]` nor `[key type options]` is refused too.
- **A table map still writes out `:indexes`, `:gates` and `:on-change`**: `[]` and nil written out for none.
- **Gates still write out all four operations** when a table has them, `"false"` for none allowed.

**Why:** tables stay readable, and the unsaid answer is the safe one. A table's columns are read far more often than its pool settings, and most of them have one option or none; written out five times each, the one that matters (`{:references :shop/id}`, `{:optional? true}`) sits among four `false`s and nils. Every unsaid option is the side that refuses more: a column that is NOT NULL, not unique, without a default or a reference takes no data it shouldn't. A forgotten `{:optional? true}` shows at the first insert of a nil, a refusal, not data quietly let in. A table's own keys are different: `:gates nil` is the choice to have no row rules, which is the open side, and a forgotten `:gates` would read the same; so they stay written out.

**Instead of:**
- every column writing out all five options (v0.1.2): rejected, long rows hide the one option that matters
- a table map's `:indexes`, `:gates` and `:on-change` left unsaid too: `:gates` unsaid would mean no row rules, the open side, and a forgotten one would look chosen
- the options map always written, `{}` for none: one more thing on most rows, saying nothing
