# Reference material (read-only)

This folder is the **port source** for the Java rewrite. It is not compiled by
Maven and is safe to delete once the port (US-01..US-17) is complete.

- `SPEC.md` — the behavioral specification being ported.
- `go/` — the original Go implementation (source + tests), the ground truth for
  porting each step's behavior.
- `fixtures/` — sample `.jrxml` files for tests.

## Porting notes

- Each Java story references the corresponding Go file(s) under `go/` to port.
- **Typo dictionary** (commitmsg, US-08): the Go version used
  `github.com/client9/misspell v0.3.4`. Bundle its `mwords.go` dictionary data
  as a Java resource (or a plain word list) in the `commitmsg` step — there is
  no direct Maven equivalent.
