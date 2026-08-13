#!/bin/sh
# Build libigropyr-gzip (the C shim for (igropyr gzip)).
# Requires zlib headers + library (present in the base system or via
# the platform's package manager on every supported OS).
set -eu
cd "$(dirname "$0")"

if [ "$(uname)" = "Darwin" ]; then
  cc -O2 -shared -fPIC c/gzip-shim.c -lz -o libigropyr-gzip.dylib
  echo "built libigropyr-gzip.dylib"
else
  cc -O2 -shared -fPIC c/gzip-shim.c -lz -o libigropyr-gzip.so
  echo "built libigropyr-gzip.so"
fi
