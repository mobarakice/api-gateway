# 0012 Routing: host table + segment radix trie

- Status: Accepted (2026-11-19, M2)

## Context
Routing runs on every request and must scale to 10K+ routes with no per-request allocation and no
regex (CLAUDE.md hot-path rules).

## Options
1. Linear scan of compiled matchers (regex or predicate lists).
2. Character-level radix tree (path bytes).
3. **Host table, then a trie over path segments** with static / parameter / prefix edges.

## Decision
Option 3.
- Host scope order: exact host → wildcard suffixes (`*.example.com`, longest first) → host-less routes.
  The first scope with a matching route wins. Hosts are lower-cased; ports are ignored.
- Per node, in order: static child (open-addressing table looked up by a substring *region*, no
  substring created), parameter child (`{name}` = exactly one non-empty segment), then prefix routes
  ending here. Backtracking makes `/users/admin/orders/1` reach `/users/{id}/orders/{oid}` when the
  static `admin` branch has no match.
- Precedence: static > parameter > prefix; longer prefix > shorter. Prefix matches stop at segment
  boundaries (`/api` matches `/api` and `/api/x`, not `/apiary`).
- At one node, candidates are ordered by priority, then number of header/query predicates, then
  declaration order; the first whose method bitmask, headers and query pairs match wins.
- Parameter values are recorded as offsets in a per-event-loop `RouteMatch`; substrings are created
  only if a plugin asks for a parameter.
- The validator rejects two routes that would match exactly the same requests at the same priority.

## Performance (dev host, JMH, `-prof gc`; not reference-host numbers)
`RouterBenchmark.lookup`, 4096-request mix of exact, template, prefix and miss lookups across three host
scopes, Oracle GraalVM 25.0.4 JVM, macOS arm64:

| routes | ns/op | B/op |
|---|---|---|
| 10 | 56.1 ± 0.8 | ≈ 0 |
| 1 000 | 69.4 ± 0.2 | ≈ 0 |
| 10 000 | 72.8 ± 0.1 | ≈ 0 |

Cost grows with path depth, not route count.

## Memory
One node per distinct segment per host scope. Wildcard scopes are checked linearly (expected to be few).

## Native Image
Plain arrays and records; no reflection or resources.

## Operational impact
Ambiguous configurations are rejected at validation time with the conflicting route indexes.
