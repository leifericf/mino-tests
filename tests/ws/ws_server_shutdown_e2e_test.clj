(require "tests/test")
(require '[mino.ws :as ws])

;; Websocket server E2E across a process boundary: a child mino
;; process serves the {:ws f} upgrade through mino.http.server, and
;; this process drives it with mino.ws, both ends the shipped
;; implementations (the client E2E next door scripts its server from
;; raw prims; here nothing is scripted). The child traps SIGTERM with
;; the run-server stop-then-exit idiom, so the battery proves the
;; graceful path end to end: the parked client receives a going-away
;; 1001 close, the connection finishes through the close handshake,
;; the child exits with the handler's 0, and the port refuses
;; connections afterwards.
;;
;; The child is started under a backgrounded shell supervisor that
;; records the child's pid (its shell execs the binary, so the pid
;; names the mino process) and, once it ends, its exit code. A child
;; cannot target itself through $PPID: the sh primitive interposes a
;; wrapper shell. The child self-bounds with a 10 s poll loop and a
;; fallback (exit 99), so a wedged run never leaks a process.
;;
;; POSIX-only (SIGTERM); Windows runs nothing here. MINO_BIN
;; overrides the binary, as everywhere in this repo.

(def ^:private wsse2-posix? (nil? (getenv "OS")))

(def ^:private wsse2-bin (or (getenv "MINO_BIN") "./mino/mino"))

(def ^:private wsse2-root "/tmp/mino-ws-server-e2e")

(defn- wsse2-reset-root! []
  (try (rm-rf wsse2-root) (catch _ nil))
  (mkdir-p wsse2-root))

(defn- wsse2-await
  "Poll pred every 20ms for up to 10s; true once it holds."
  [pred]
  ((fn wait [n]
     (cond (pred)    true
           (zero? n) false
           :else     (do (thread-sleep 20) (wait (dec n)))))
   500))

(defn- wsse2-await-file [path]
  (wsse2-await #(file-exists? path)))

(def ^:private wsse2-pidf  (str wsse2-root "/child.pid"))
(def ^:private wsse2-outf  (str wsse2-root "/child.out"))
(def ^:private wsse2-exitf (str wsse2-root "/child.exit"))

(defn- wsse2-launch-child!
  "Start the child program under the supervisor and return its pid."
  [script]
  (let [child    (str wsse2-root "/child.clj")
        launcher (str wsse2-root "/launch.sh")]
    (spit child script)
    (spit launcher (str "{\n"
                        "  sh -c 'echo $$ > " wsse2-pidf
                        "; exec " wsse2-bin " " child
                        " > " wsse2-outf " 2>&1' &\n"
                        "  wait $!\n"
                        "  echo $? > " wsse2-exitf "\n"
                        "} > /dev/null 2>&1 &\n"))
    (sh "sh" launcher)
    (is (wsse2-await-file wsse2-pidf) "supervisor published the child pid")
    (re-find #"\d+" (slurp wsse2-pidf))))

(defn- wsse2-child-exit
  "Await and return the supervised child's exit code as an int."
  [pid]
  (is (wsse2-await-file wsse2-exitf)
      (str "child " pid " exited within the deadline"))
  (read-string (re-find #"\d+" (slurp wsse2-exitf))))

(defn- wsse2-kind
  [thunk]
  (try (do (thunk) :ok) (catch e (:mino/kind e))))

(def ^:private wsse2-child-program
  (str "(require '[mino.http.server :as srv])\n"
       "(require '[mino.ws :as ws])\n"
       "(def s (srv/run-server\n"
       "        (fn [req]\n"
       "          (if (= \"/ws\" (:uri req))\n"
       "            {:ws (fn [h]\n"
       "                   ((fn go []\n"
       "                      (let [m (ws/ws-recv h)]\n"
       "                        (when-not (= :close (:opcode m))\n"
       "                          (ws/ws-send h (:payload m))\n"
       "                          (go))))))}\n"
       "            {:status 200 :body \"pong\"}))\n"
       "        {:port 0}))\n"
       "(spit \"" wsse2-root "/port.txt\" (str (:port s)))\n"
       "(on-signal :term (fn [] ((:stop s)) (exit 0)))\n"
       "(spit \"" wsse2-root "/ready\" \"up\")\n"
       "((fn wait [n] (when (pos? n)"
       " (thread-sleep 50) (wait (dec n)))) 200)\n"
       "(exit 99)\n"))

(when wsse2-posix?
  (deftest ws-server-upgrade-echoes-and-sigterm-sends-going-away
    (wsse2-reset-root!)
    (let [pid (wsse2-launch-child! wsse2-child-program)]
      (is (wsse2-await-file (str wsse2-root "/ready"))
          "child signalled its trap is installed")
      (let [port (read-string
                  (re-find #"\d+" (slurp (str wsse2-root "/port.txt"))))
            h (ws/ws-connect (str "ws://127.0.0.1:" port "/ws"))]
        ;; the shipped client against the shipped server, echo whole
        (is (nil? (ws/ws-send h "hello across processes")))
        (is (= {:opcode :text :payload "hello across processes"}
               (ws/ws-recv h {:timeout 10000})))
        (let [payload (byte-array (map #(rem % 251) (range 4096)))]
          (ws/ws-send h payload)
          (let [m (ws/ws-recv h {:timeout 10000})]
            (is (= :binary (:opcode m)))
            (is (= (vec payload) (vec (:payload m)))
                "the binary echo is byte-identical")))
        ;; SIGTERM while the client is parked mid-recv: stop's drain
        ;; reaches it as a going-away close before the process exits
        (let [parked (future (ws/ws-recv h {:timeout 10000}))]
          (sh "kill" "-TERM" pid)
          (is (= {:opcode :close :code 1001 :reason ""}
                 (deref parked 10000 :timeout))
              "the going-away close arrived, whole")
          (is (= :ws/closed (wsse2-kind #(ws/ws-recv h)))
              "the connection finished through the close handshake"))
        (is (= 0 (wsse2-child-exit pid))
            "the trap+stop path exits with the handler's 0, never 128+15")
        (is (thrown? (net-connect "127.0.0.1" port
                                  {:connect-timeout 2000}))
            "after shutdown a fresh connect is refused")))
    ;; drain this process's worker grant before the runner exits
    (is (wsse2-await #(zero? (mino-thread-count)))
        "the runner's future released its worker slot")))
