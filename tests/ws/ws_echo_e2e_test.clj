(require "tests/test")
(require '[mino.ws :as ws])

;; Websocket client E2E: mino.ws driven end to end against an echo
;; websocket server scripted from mino's own net-listen plus the
;; server-role frame codec, in process on 127.0.0.1. One connection
;; lives through the whole lifecycle: upgrade, a mixed text/binary
;; message battery (including payloads far past one socket read),
;; server pings interleaved with the traffic (the client owes a masked
;; pong for each, below its API), fragmented echoes reassembling on
;; the client, and both directions of the close handshake. Decoding
;; the client's bytes at :role :server is what proves every client
;; frame arrived masked.

(defn- we2-bb
  [& xs]
  (byte-array (mapcat #(cond (bytes? %) (vec %)
                             (string? %) (map int %)
                             (number? %) [%]
                             :else (vec %))
                      xs)))

(defn- we2-pattern
  "n deterministic non-text bytes seeded by k."
  [n k]
  (byte-array (map #(rem (+ % k) 251) (range n))))

(defn- we2-upgrade!
  "Server side of the RFC 6455 upgrade on an accepted conn. Returns
  the leftover bytes past the request head."
  [c]
  (loop [buf (byte-array [])]
    (let [r (http-parse-request buf)]
      (if (= :done (:status r))
        (let [key (get (:headers r) "sec-websocket-key")]
          (net-write c (str "HTTP/1.1 101 Switching Protocols\r\n"
                            "Upgrade: websocket\r\n"
                            "Connection: Upgrade\r\n"
                            "Sec-WebSocket-Accept: " (ws-accept-key key)
                            "\r\n\r\n"))
          (:leftover r))
        (let [b (net-read c 65536)]
          (when (nil? b)
            (throw "eof during the upgrade request"))
          (recur (we2-bb buf b)))))))

(defn- we2-echo-frame!
  "Echo one data message back unmasked; every fourth echo goes out as
  three fragments so the client reassembles a running conversation."
  [c f n]
  (if (and (= :text (:opcode f)) (= 0 (rem n 4)) (>= (count (:payload f)) 3))
    (let [p (:payload f)
          third (quot (count p) 3)]
      (net-write c (ws-encode-frame {:opcode :text :fin? false
                                     :payload (subs p 0 third)}))
      (net-write c (ws-encode-frame {:opcode :continuation :fin? false
                                     :payload (subs p third (* 2 third))}))
      (net-write c (ws-encode-frame {:opcode :continuation
                                     :payload (subs p (* 2 third))})))
    (net-write c (ws-encode-frame {:opcode (:opcode f)
                                   :payload (:payload f)}))))

(defn- we2-serve-echo!
  "The whole scripted server: upgrade, then echo every data message,
  sending a ping before every fifth echo. A close frame is answered
  and ends the loop. Returns {:close f :pongs [payloads] :echoed n}."
  [c]
  (loop [pending []
         buf (we2-upgrade! c)
         echoed 0
         pings 0
         pongs []]
    (if-let [f (first pending)]
      (case (:opcode f)
        :close
        (do (net-write c (ws-encode-frame
                          (if (:code f)
                            {:opcode :close :code (:code f)}
                            {:opcode :close})))
            {:close f :pongs pongs :echoed echoed :pings pings})

        :pong
        (recur (vec (rest pending)) buf echoed pings
               (conj pongs (apply str (map char (seq (:payload f))))))

        :ping
        (do (net-write c (ws-encode-frame {:opcode :pong
                                           :payload (:payload f)}))
            (recur (vec (rest pending)) buf echoed pings pongs))

        (let [n (inc echoed)
              ping? (= 0 (rem n 5))]
          (when ping?
            (net-write c (ws-encode-frame {:opcode :ping
                                           :payload (str "k" n)})))
          (we2-echo-frame! c f n)
          (recur (vec (rest pending)) buf n
                 (if ping? (inc pings) pings) pongs)))
      (let [r (ws-decode-frames buf {:role :server})]
        (if (seq (:frames r))
          (recur (vec (:frames r)) (:rest r) echoed pings pongs)
          (let [b (net-read c 65536)]
            (when (nil? b)
              (throw (str "server eof after " echoed " echoes")))
            (recur [] (we2-bb (:rest r) b) echoed pings pongs)))))))

(defn- we2-start-server
  "One-connection echo server on a kernel-chosen port; serve-fn runs
  on the accepted conn and its value is delivered on :done."
  [serve-fn]
  (let [port-p (promise)
        done-p (promise)]
    (future
      (let [l (net-listen "127.0.0.1" 0 {})]
        (deliver port-p (net-listener-port l))
        (try
          (let [c (net-accept l {:accept-timeout 20000
                                 :read-timeout 20000})]
            (try
              (deliver done-p (serve-fn c))
              (finally (try (net-close c) (catch e nil)))))
          (catch e (deliver done-p {:server-error e}))
          (finally (try (net-close l) (catch e nil))))))
    {:port (deref port-p 20000 ::timeout) :done done-p}))

(deftest ws-e2e-full-lifecycle-against-an-echo-server
  (let [srv (we2-start-server we2-serve-echo!)
        h (ws/ws-connect (str "ws://127.0.0.1:" (:port srv) "/echo")
                         {:read-timeout 15000})
        big-text (apply str (repeat 20000 "0123456789"))
        big-binary (we2-pattern 65536 7)]
    (is (not= ::timeout (:port srv)))
    ;; 20 short text rounds: every fourth echo comes back fragmented
    ;; and every fifth is preceded by a server ping the client answers
    ;; below the API; each recv is still exactly the echoed message.
    (dotimes [i 20]
      (let [msg (str "message-" i "-" (apply str (repeat (inc i) "x")))]
        (ws/ws-send h msg)
        (is (= {:opcode :text :payload msg} (ws/ws-recv h))
            (str "round " i " echoed intact"))))
    ;; Binary rounds, including one spanning several socket reads.
    (doseq [payload [(we2-pattern 1 3) (we2-pattern 300 11) big-binary]]
      (ws/ws-send h payload)
      (let [m (ws/ws-recv h)]
        (is (= :binary (:opcode m)))
        (is (= (vec payload) (vec (:payload m)))
            (str (count payload) "-byte binary echoed intact"))))
    ;; A 200 KB text message: past both the frame 16-bit length field
    ;; and the 64 KiB read chunk.
    (ws/ws-send h big-text)
    (is (= {:opcode :text :payload big-text} (ws/ws-recv h)))
    ;; Client-initiated close handshake ends the conversation.
    (is (nil? (ws/ws-close h {:code 1000 :reason "done"})))
    (let [sv (deref (:done srv) 20000 ::timeout)]
      (is (= 24 (:echoed sv)) "the server echoed every message")
      (is (= :close (:opcode (:close sv))))
      (is (= 1000 (:code (:close sv))))
      (is (= "done" (:reason (:close sv))))
      (is (= (:pings sv) (count (:pongs sv)))
          "every server ping was answered by a masked pong")
      (is (= (map #(str "k" (* 5 (inc %))) (range (:pings sv)))
             (:pongs sv))
          "each pong echoed its ping payload in order"))))

(deftest ws-e2e-server-initiated-close-surfaces-as-data
  (let [srv (we2-start-server
             (fn [c]
               (let [rest0 (we2-upgrade! c)]
                 (net-write c (ws-encode-frame {:opcode :text
                                                :payload "last words"}))
                 (net-write c (ws-encode-frame {:opcode :close :code 1001
                                                :reason "maintenance"}))
                 ;; The client owes a masked close reply, then EOF.
                 (loop [buf rest0]
                   (let [r (ws-decode-frames buf {:role :server})]
                     (if-let [f (first (:frames r))]
                       {:reply f :eof (net-read c 1)}
                       (let [b (net-read c 65536)]
                         (when (nil? b) (throw "eof before the close reply"))
                         (recur (we2-bb (:rest r) b)))))))))
        h (ws/ws-connect (str "ws://127.0.0.1:" (:port srv) "/bye")
                         {:read-timeout 15000})]
    (is (= {:opcode :text :payload "last words"} (ws/ws-recv h)))
    (is (= {:opcode :close :code 1001 :reason "maintenance"}
           (ws/ws-recv h))
        "the peer's close returns as data")
    (let [sv (deref (:done srv) 20000 ::timeout)]
      (is (= :close (:opcode (:reply sv))))
      (is (= 1001 (:code (:reply sv))) "the reply echoes the close code")
      (is (nil? (:eof sv)) "the client dropped the connection"))
    (is (nil? (ws/ws-close h)) "close after the handshake is a no-op")))

(run-tests-and-exit)
