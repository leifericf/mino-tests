;; run_signal_e2e.clj -- graceful-shutdown E2E: a child mino process
;; serves HTTP through mino.http.server/run-server, traps SIGTERM
;; with a handler that calls the server's :stop and then (exit 0).
;; The battery drives the whole idiom through the real binary from
;; outside: the in-flight request drains to its full body, at-exit
;; hooks still run on the trapped path, the exit code is the
;; handler's 0 (never 128+15), and the port refuses connections
;; afterwards.
;;
;; The child is started under a backgrounded shell supervisor that
;; records the child's pid (its shell execs the binary, so the pid
;; names the mino process) and, once it ends, its exit code. A child
;; cannot target itself through $PPID: the sh primitive interposes a
;; wrapper shell. The child self-bounds with a 10 s poll loop and a
;; fallback (exit 99), so a wedged run never leaks a process.
;;
;; POSIX-only (SIGTERM); Windows runs nothing here. Needs a binary
;; carrying the :signal capability group (on-signal / at-exit).
;; MINO_BIN overrides the binary, as everywhere in this repo.
;;
;; Usage:
;;   ./mino/mino tests/run_signal_e2e.clj     (or: task signal-e2e)

(require "tests/test")

(def ^:private sig-posix? (nil? (getenv "OS")))

(def ^:private mino-bin (or (getenv "MINO_BIN") "./mino/mino"))

(def ^:private root "/tmp/mino-signal-e2e")

(defn- reset-root! []
  (try (rm-rf root) (catch _ nil))
  (mkdir-p root))

(defn- await-pred
  "Poll pred every 20ms for up to 10s; true once it holds."
  [pred]
  ((fn wait [n]
     (cond (pred)    true
           (zero? n) false
           :else     (do (thread-sleep 20) (wait (dec n)))))
   500))

(defn- await-file [path]
  (await-pred #(file-exists? path)))

(def ^:private pidf  (str root "/child.pid"))
(def ^:private outf  (str root "/child.out"))
(def ^:private exitf (str root "/child.exit"))

(defn- launch-child!
  "Start the child program in a background mino process and return
  its pid as a string. A launcher script backgrounds a supervisor
  subshell that starts the child, waits for it, and records its exit
  code (128+signo when a signal killed it). The child's shell writes
  its own pid and then execs the binary, so the recorded pid names
  the mino process. The supervisor redirects to /dev/null: anything
  holding the launching sh call's capture pipe open would block that
  call until the child exited."
  [script]
  (let [child    (str root "/child.clj")
        launcher (str root "/launch.sh")]
    (spit child script)
    (spit launcher (str "{\n"
                        "  sh -c 'echo $$ > " pidf
                        "; exec " mino-bin " " child
                        " > " outf " 2>&1' &\n"
                        "  wait $!\n"
                        "  echo $? > " exitf "\n"
                        "} > /dev/null 2>&1 &\n"))
    (sh "sh" launcher)
    (is (await-file pidf) "supervisor published the child pid")
    (re-find #"\d+" (slurp pidf))))

(defn- child-exit
  "Await and return the supervised child's exit code as an int."
  [pid]
  (is (await-file exitf) (str "child " pid " exited within the deadline"))
  (read-string (re-find #"\d+" (slurp exitf))))

(defn- get-request
  "One origin-form GET as wire bytes, asking the connection to close
  so the served worker ends with the response."
  [target]
  (byte-array (map int (str "GET " target " HTTP/1.1\r\n"
                            "Host: t.example\r\n"
                            "Connection: close\r\n\r\n"))))

(defn- read-response
  "Read one complete response off c; the parsed map, or nil when the
  peer closed before a full response arrived."
  [c]
  (loop [acc []]
    (let [r (http-parse-response (byte-array acc))]
      (if (= :done (:status r))
        r
        (let [b (try (net-read c 65536) (catch e nil))]
          (if b
            (recur (into acc (vec b)))
            (let [fin (http-parse-response (byte-array acc) {:eof true})]
              (when (= :done (:status fin)) fin))))))))

(defn- body-text [r]
  (apply str (map char (seq (:body r)))))

(defn- fetch
  "Connect, send one closing GET, read the response back, close."
  [port target]
  (let [c (net-connect "127.0.0.1" port
                       {:read-timeout 8000 :write-timeout 8000})]
    (try
      (net-write c (get-request target))
      (read-response c)
      (finally (try (net-close c) (catch e nil))))))

(when sig-posix?
  (deftest sigterm-trap-stops-the-server-drains-and-runs-exit-hooks
    ;; The child's /slow handler writes in-flight evidence, sleeps
    ;; well inside stop's join grace, and answers a distinctive body;
    ;; the parent raises SIGTERM exactly while that handler is
    ;; mid-sleep. An at-exit hook registered before the trap must
    ;; still run on the trapped path, after the drain.
    (reset-root!)
    (let [portf    (str root "/port.txt")
          ready    (str root "/ready")
          inflight (str root "/inflight")
          hookf    (str root "/hook.txt")
          pid
          (launch-child!
           (str "(require '[mino.http.server :as srv])\n"
                "(at-exit (fn [] (spit \"" hookf "\" \"hook-ran\\n\")))\n"
                "(def s (srv/run-server\n"
                "        (fn [req]\n"
                "          (if (= \"/slow\" (:uri req))\n"
                "            (do (spit \"" inflight "\" \"in\")\n"
                "                (thread-sleep 500)\n"
                "                {:status 200 :body \"drained-ok\"})\n"
                "            {:status 200 :body \"pong\"}))\n"
                "        {:port 0}))\n"
                "(spit \"" portf "\" (str (:port s)))\n"
                "(on-signal :term (fn [] ((:stop s)) (exit 0)))\n"
                "(spit \"" ready "\" \"up\")\n"
                "((fn wait [n] (when (pos? n)"
                " (thread-sleep 50) (wait (dec n)))) 200)\n"
                "(exit 99)\n"))]
      (is (await-file ready) "child signalled its trap is installed")
      (let [port (read-string (re-find #"\d+" (slurp portf)))]
        (let [r (fetch port "/ping")]
          (is (= 200 (:code r)) "the server answers before the signal")
          (is (= "pong" (body-text r))))
        (let [slow (future (fetch port "/slow"))]
          (is (await-file inflight) "the slow handler is mid-request")
          (sh "kill" "-TERM" pid)
          (let [r (deref slow 10000 :timeout)]
            (is (map? r) "the in-flight response arrived, whole")
            (when (map? r)
              (is (= 200 (:code r)))
              (is (= "drained-ok" (body-text r))
                  "stop drained the in-flight request before exit")))
          (is (= 0 (child-exit pid))
              "the trap+stop path exits with the handler's 0, never 128+15")
          (is (= "hook-ran\n" (slurp hookf))
              "the at-exit hook ran on the trapped path")
          (is (thrown? (net-connect "127.0.0.1" port
                                    {:connect-timeout 2000}))
              "after shutdown a fresh connect is refused"))))
    ;; drain this process's worker grant before the runner exits
    (is (await-pred #(zero? (mino-thread-count)))
        "the runner's future released its worker slot")))

(run-tests-and-exit)
