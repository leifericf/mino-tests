(require "tests/test")
(require '[clojure.string :as str])

;; Digest/HMAC/crc32 E2E: cross-checks the mino digest prims against
;; python3 (hashlib / hmac / zlib), the external oracle the mino repo's
;; C+mino-only rule keeps out of its own suite. Both sides generate the
;; same deterministic byte blobs from a uint32 LCG (identical recurrence
;; in both languages), so no binary data crosses a process boundary;
;; text fixtures are hashed straight off disk by both sides. Tools
;; missing from PATH skip their lane loudly.

(def ^:private python-available?
  (try (sh "python3" "-c" "import hashlib, hmac, zlib") true
       (catch e false)))

(def ^:private mask32 0xFFFFFFFF)

(defn- lcg-byte-vals
  "Deterministic byte values from the shared xorshift32 generator:
  x ^= x << 13; x &= 2^32-1; x ^= x >> 17; x ^= x << 5; x &= 2^32-1,
  byte = bits 16..23. Every intermediate stays under 2^45, so no value
  ever leaves the fixnum range (mino's bitwise prims reject bigint).
  The same recurrence runs in the python oracle; the seeds are the
  contract, and a nonzero seed never reaches the zero state."
  [seed n]
  (let [acc (transient [])]
    (loop [x seed i 0]
      (if (= i n)
        (persistent! acc)
        (let [a  (bit-and mask32 (bit-xor x (bit-shift-left x 13)))
              b  (bit-xor a (unsigned-bit-shift-right a 17))
              x' (bit-and mask32 (bit-xor b (bit-shift-left b 5)))]
          (conj! acc (bit-and 0xFF (unsigned-bit-shift-right x' 16)))
          (recur x' (inc i)))))))

(defn- lcg-bytes [seed n]
  (byte-array (lcg-byte-vals seed n)))

(def ^:private oracle-gen
  "Python preamble that regenerates the shared xorshift32 bytes: argv
  holds key-seed, data-seed, data-size, key-size in that order; k is
  the key blob, out the data blob. Digest lanes reuse out; the hmac
  lane adds the call."
  "import sys, hashlib, hmac
kseed = int(sys.argv[1]); dseed = int(sys.argv[2]); n = int(sys.argv[3])

def gen(gseed, gn):
    y = gseed; b = bytearray()
    for _ in range(gn):
        y ^= (y << 13) & 0xFFFFFFFF
        y ^= y >> 17
        y ^= (y << 5) & 0xFFFFFFFF
        y &= 0xFFFFFFFF
        b.append((y >> 16) & 0xFF)
    return bytes(b)

k = gen(kseed, int(sys.argv[4]))
out = gen(dseed, n)
")

(defn- py [snippet & args]
  (str/trim (apply sh! "python3" "-c" snippet (vec args))))

(def ^:private sizes
  "Blob sizes sweeping the 64-byte block boundaries and the streaming
  regime: empty, single, 55/56/57 (sha padding straddle), 63/64/65
  (block straddle), 127/128/129, then kilobyte-and-up streaming sizes."
  [0 1 55 56 57 63 64 65 127 128 129 1000 4096 65536 1048576])

(defn- oracle-digest [alg n]
  (py (str oracle-gen
          "sys.stdout.write(hashlib." alg "(out).hexdigest())")
      "7" "42" (str n) "0"))

(deftest sha256-sha1-md5-match-python-hashlib-across-sizes
  (if python-available?
    (doseq [n sizes]
      (is (= (oracle-digest "sha256" n)
             (hex-encode (sha256 (lcg-bytes 42 n))))
          (str "sha256 size " n))
      (is (= (oracle-digest "sha1" n)
             (hex-encode (sha1 (lcg-bytes 42 n))))
          (str "sha1 size " n))
      (is (= (oracle-digest "md5" n)
             (hex-encode (md5 (lcg-bytes 42 n))))
          (str "md5 size " n)))
    (is true "python3 unavailable; lane skipped")))

(deftest hmac-sha256-matches-python-across-key-and-data-sizes
  ;; Key sizes cross the HMAC block size (64) and the hash-down
  ;; threshold (block-size-plus); data sizes cross one block.
  (if python-available?
    (doseq [kn [0 1 20 31 32 33 64 65 131 200]
            dn [0 1 50 64 152]]
      (let [code (str oracle-gen
                      "sys.stdout.write(hmac.new(k, out, "
                      "hashlib.sha256).hexdigest())")]
        (is (= (py code "7" "42" (str dn) (str kn))
               (hex-encode (hmac-sha256 (lcg-bytes 7 kn)
                                        (lcg-bytes 42 dn))))
            (str "hmac key " kn " data " dn))))
    (is true "python3 unavailable; lane skipped")))

(deftest crc32-matches-python-zlib-across-sizes
  (if python-available?
    (doseq [n sizes]
      (let [code "import sys, zlib
dseed = int(sys.argv[2]); n = int(sys.argv[3])
x = dseed; out = bytearray()
for _ in range(n):
    x ^= (x << 13) & 0xFFFFFFFF
    x ^= x >> 17
    x ^= (x << 5) & 0xFFFFFFFF
    x &= 0xFFFFFFFF
    out.append((x >> 16) & 0xFF)
sys.stdout.write(str(zlib.crc32(bytes(out))))"]
        (is (= (py code "7" "42" (str n) "0")
               (str (crc32 (lcg-bytes 42 n))))
            (str "crc32 size " n))))
    (is true "python3 unavailable; lane skipped")))

(def ^:private fixture-files
  "Text fixtures every checkout has; both sides hash the same bytes."
  ["README.md" "mino.edn" "tests/tls/fixtures/README.md"
   "tests/digest/digest_e2e_test.clj"])

(deftest fixture-file-hashes-match-python-on-disk
  (if python-available?
    (doseq [f fixture-files]
      (is (= (py "import sys, hashlib
data = open(sys.argv[1], 'rb').read()
sys.stdout.write(hashlib.sha256(data).hexdigest())" f)
             (hex-encode (sha256 (slurp f))))
          (str "file " f)))
    (is true "python3 unavailable; lane skipped")))
