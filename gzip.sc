#!chezscheme
;;; (igropyr gzip) -- gzip compression behind a small C shim.
;;;
;;; (gzip-compress bv level) -> a gzip-format bytevector (browser
;;; Content-Encoding: gzip), or #f on failure. level 1..9 (6 is a good
;;; default). (gzip-acceptable? v) decides whether an Accept-Encoding
;;; header value allows gzip.
;;;
;;; This is the C-shim binding: the whole deflate sequence
;;; (deflateInit2 .. deflate .. deflateEnd) runs inside ONE C call in
;;; libigropyr-gzip, so which zlib serves it is settled once, by the
;;; dynamic linker, when the shim is loaded -- never symbol by symbol
;;; from the Scheme side. The Igropyr main tree ships a pure-Scheme
;;; binding with the same exports; reach for this one where the
;;; in-process zlib story makes a direct FFI binding awkward -- e.g. a
;;; runtime that embeds and exports its own zlib next to the system
;;; one, or a build where no safe copy resolves by name.

(library (igropyr gzip)
  (export gzip-compress gzip-acceptable?)
  (import (chezscheme) (igropyr platform))

  ;; Resolution order: IGROPYR_GZIP_SO env var > relative igropyr dirs >
  ;; plain name for a copy on the loader path.
  (define so-loaded
    (begin
      (ensure-supported-platform!)
      (load-first-shared-object! 'gzip-shim
        (append
          (let ((e (getenv "IGROPYR_GZIP_SO"))) (if e (list e) '()))
          (list "igropyr/libigropyr-gzip.dylib"
                ".build-links/igropyr/libigropyr-gzip.dylib"
                "libigropyr-gzip.dylib"
                "igropyr/libigropyr-gzip.so"
                ".build-links/igropyr/libigropyr-gzip.so"
                "libigropyr-gzip.so")))))

  ;; int igropyr_gzip_compress(src, src_len, dst, cap_inout, level):
  ;; 0 on success, with *cap_inout rewritten to the produced size.
  (define c-compress
    (foreign-procedure "igropyr_gzip_compress"
      (u8* unsigned-32 u8* u8* int) int))

  ;; zlib's stream counters are 32-bit. The largest n whose output
  ;; bound (n + n/1000 + 128) still fits in one is 4290676491; past
  ;; that the stream cannot be described to the shim in one call, so
  ;; refuse with #f up front instead of overflowing the counters.
  (define max-input-size 4290676491)

  ;; Compress bv to gzip format. Returns #f on any zlib error.
  (define (gzip-compress bv level)
    (let ((n (bytevector-length bv)))
      (and (<= n max-input-size)
           (let* ((bound (+ n (quotient n 1000) 128)) ; safe deflate upper bound
                  (dst (make-bytevector bound))
                  (cap (make-bytevector 4)))
             (bytevector-u32-native-set! cap 0 bound)
             (and (fx= 0 (c-compress bv n dst cap level))
                  (let ((out (bytevector-u32-native-ref cap 0)))
                    (bytevector-truncate! dst out)
                    dst))))))

  ;; does an Accept-Encoding header value allow gzip? Case-insensitive
  ;; search in place: no downcased copy, no per-position substring.
  ;; Accept-Encoding is a list of "coding[;q=value]" entries: a bare
  ;; substring search would compress for a client that explicitly said
  ;; "gzip;q=0" (RFC 9110: q=0 means NOT acceptable -- clients send it
  ;; precisely because they cannot decode it) and would also fire on an
  ;; unrelated coding that merely contains the letters.
  (define (gzip-acceptable? accept-encoding)
    (and accept-encoding
         (let ((n (string-length accept-encoding)))
           ;; Walk every comma-separated entry and keep the most specific
           ;; verdict: an explicit "gzip" (or its legacy alias "x-gzip",
           ;; RFC 9110 8.4.1.3) overrides a wildcard, so "*;q=0, gzip"
           ;; still compresses. Only when no explicit entry names gzip
           ;; does the wildcard decide.
           (let entry ((start 0) (explicit #f) (star #f))
             (if (>= start n)
                 (if (eq? explicit #f) (eq? star #t) explicit)
                 (let* ((end (let scan ((i start))
                               (cond ((>= i n) n)
                                     ((char=? (string-ref accept-encoding i) #\,) i)
                                     (else (scan (+ i 1))))))
                        (semi (let scan ((i start))
                                (cond ((>= i end) end)
                                      ((char=? (string-ref accept-encoding i) #\;) i)
                                      (else (scan (+ i 1))))))
                        (name (trim accept-encoding start semi))
                        (ok (not (q-zero? accept-encoding semi end))))
                   (cond
                     ((or (string-ci=? name "gzip") (string-ci=? name "x-gzip"))
                      (entry (+ end 1) ok star))
                     ((string=? name "*") (entry (+ end 1) explicit ok))
                     (else (entry (+ end 1) explicit star)))))))))

  (define (trim s start end)
    (let* ((b (let scan ((i start))
                (if (and (< i end) (memv (string-ref s i) '(#\space #\tab)))
                    (scan (+ i 1)) i)))
           (e (let scan ((i end))
                (if (and (> i b) (memv (string-ref s (- i 1)) '(#\space #\tab)))
                    (scan (- i 1)) i))))
      (substring s b e)))

  ;; Is there a q= parameter equal to zero in [from,to)? Parameters are
  ;; ';'-separated, and the NAME must be exactly "q" -- ";xq=0" is a
  ;; different parameter and must not be read as a quality value.
  (define (q-zero? s from to)
    (let param ((start from))
      (and (< start to)
           (let* ((semi (let scan ((i (if (and (< start to)
                                               (char=? (string-ref s start) #\;))
                                          (+ start 1)
                                          start)))
                          (cond ((>= i to) to)
                                ((char=? (string-ref s i) #\;) i)
                                (else (scan (+ i 1))))))
                  (body-start (if (and (< start to)
                                       (char=? (string-ref s start) #\;))
                                  (+ start 1)
                                  start))
                  (eq-pos (let scan ((i body-start))
                            (cond ((>= i semi) #f)
                                  ((char=? (string-ref s i) #\=) i)
                                  (else (scan (+ i 1)))))))
             (if (and eq-pos
                      (string-ci=? (trim s body-start eq-pos) "q")
                      (let ((v (string->number (trim s (+ eq-pos 1) semi))))
                        (and v (zero? v))))
                 #t
                 (param semi))))))
)
