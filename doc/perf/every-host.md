# Every-host gains (M-10)

The speed gains that landed on jank first, decided host by host. A row
is ON (the host has the cost the gain removes; the gain is wired and
measured before/after), N/A (the host has no such cost; the reason is
stated), or NOT-YET (the blocker, with the error). Hosts: jvm, jolt,
lpy (basilisp), cljs, cljw, phel, lg (let-go). Jank's column is the
reference state.

| gain | jvm | jolt | lpy | cljs | cljw | phel | lg |
|---|---|---|---|---|---|---|---|
| :syntax-quote-source? | | | | | | | |
| :cohost-inst? | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| :prim-kind-test | | | | | | | |
| :defn-fn-name? | | | | | | | |
| :rewrite-inline? | | | | | | | |
| tag type tests (M-4.1) | | | | | | | |
| bound RT arms (M-4.2/3) | | | | | | | |
| :native-hints (H1) | | | | | | | |
| Numbers-minus as `-` | | | | | | | |

## The rows

- **:cohost-inst?** -- the bare tag test for a cohost type instance.
  Only jank is a :cohost-types host (the adapter contract says so:
  deftype/defrecord/defprotocol are the adapter's own macros there
  and dispatch keys off descriptors). On every other host a user type
  is a host type and the host's own ops take it; there is no
  descriptor to test for. N/A x6, by the contract, not by probing.
