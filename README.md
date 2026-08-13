# igropyr-gzip

`(igropyr gzip)` — gzip compression for
[Chez Scheme](https://cisco.github.io/ChezScheme/) behind a small C shim.

```scheme
(import (igropyr gzip))

(gzip-compress (string->utf8 "hello, gzip") 6)  ; => #vu8(#x1f #x8b ...) or #f
(gzip-acceptable? "deflate, gzip;q=1.0")        ; => #t
```

This is the **C-shim** binding: the whole deflate sequence
(`deflateInit2` .. `deflate` .. `deflateEnd`) runs inside **one C call** in
`libigropyr-gzip`, so which zlib serves it is settled once, by the dynamic
linker, when the shim is loaded — never symbol by symbol from the Scheme
side. [Igropyr](https://github.com/guenchi/Igropyr) also ships a pure-Scheme
binding with the **same exports**; this repo is a drop-in replacement for it.

Why two bindings exist at all: a host runtime may embed and export its own
zlib (Chez Scheme does, for compressed fasl files), and a second zlib
dlopened next to it puts two sets of identically named globals into one
process — a combination observed to corrupt deflate state (`deflateInit2_`
reports success, then `deflate()` faults, or emits unbounded output on
incompressible input). The main-tree binding answers that by resolving the
embedded copy directly; this shim answers it by letting the C linker close
the question. Reach for the shim when the in-process zlib story makes a
direct FFI binding awkward — a runtime whose embedded zlib cannot be
resolved by address (a PIE build, stripped dynamic symbols), or when you
want the binding pinned to a zlib of your choosing at build time.

## Dependency

`(igropyr gzip)` imports
[`(igropyr platform)`](https://github.com/guenchi/igropyr-platform) (host
detection + shared-object loading). Put it on your library path so the
import resolves.

## Build the shim

Requires zlib headers + library (in the base system, or `zlib1g-dev` /
equivalent from your package manager):

```sh
./build-gzip-shim.sh        # -> libigropyr-gzip.dylib  (or .so elsewhere)
```

The shim is found via, in order: the `IGROPYR_GZIP_SO` environment
variable, `igropyr/libigropyr-gzip.{dylib,so}` relative to the working
directory, then the plain name for a copy on the loader path.

## Exports

- `(gzip-compress bv level)` — compress a bytevector into a single
  gzip member (`windowBits` 31, i.e. browser `Content-Encoding: gzip`).
  `level` is 1..9; 6 is a good default. Returns the compressed
  bytevector, or `#f` on any zlib error — callers should treat `#f` as
  "send this uncompressed".
- `(gzip-acceptable? v)` — does an `Accept-Encoding` header value allow
  gzip? Parses entries and q-values per RFC 9110 (`gzip;q=0` refuses,
  `*` counts, an explicit `gzip` overrides a wildcard, `x-gzip` is
  accepted as the legacy alias).

## Test

```sh
ln -s . igropyr
CHEZSCHEMELIBDIRS=.:path/to/igropyr-platform \
  scheme --script test/gzip.sc
```

The round trip is checked against the system `gzip(1)` tool in a separate
process — deliberately not against an in-process inflate, which would
dlopen a second zlib and recreate the coexistence described above.

## License

MIT
