/* igropyr-gzip shim: the whole deflate sequence in one C call.
 *
 * Why a shim at all: a host runtime may embed and export its own zlib
 * (Chez Scheme does, for compressed fasl files), and a second zlib
 * dlopened next to it puts two sets of identically named globals into
 * one process -- a combination observed to corrupt deflate state
 * (deflateInit2_ reports success but deflate() faults, or emits
 * unbounded output on incompressible input). A single C entry point
 * closes the question: whichever zlib the dynamic linker settles on
 * serves the entire init/deflate/end sequence, with no per-symbol
 * lookup from the host runtime in the middle.
 */
#include <zlib.h>

/* Compress src[0..src_len) into dst as one gzip member (windowBits 31,
 * memLevel 8). On entry *dst_cap is the capacity of dst; on success it
 * becomes the number of bytes produced. Returns 0 on success, -1 if
 * deflateInit2 refuses (bad level, no memory), -2 if the output did
 * not fit in dst or the stream failed. */
int igropyr_gzip_compress(unsigned char *src, unsigned src_len,
                          unsigned char *dst, unsigned *dst_cap,
                          int level) {
    z_stream s;
    int rc;
    s.zalloc = Z_NULL; s.zfree = Z_NULL; s.opaque = Z_NULL;
    if (deflateInit2(&s, level, Z_DEFLATED, 31, 8,
                     Z_DEFAULT_STRATEGY) != Z_OK)
        return -1;
    s.next_in = src;   s.avail_in = src_len;
    s.next_out = dst;  s.avail_out = *dst_cap;
    rc = deflate(&s, Z_FINISH);
    if (rc == Z_STREAM_END)
        *dst_cap = (unsigned)s.total_out;
    deflateEnd(&s);
    return rc == Z_STREAM_END ? 0 : -2;
}

/* The version string of the zlib actually serving the shim. */
const char *igropyr_gzip_zlib_version(void) { return zlibVersion(); }
