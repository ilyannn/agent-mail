#!/usr/bin/env bb
;; sounds.bb: synthesizes the app's own notification sounds into
;; AgentMail/Resources/Sounds, from which the build copies them into the
;; app's Contents/Resources. Each one is computed sample by sample as 16-bit
;; mono PCM at 44.1 kHz, written as a WAV to a temporary directory and
;; converted to CAF with afconvert (macOS only). The output is deterministic,
;; so the .caf files are committed and only change when this script does.
;; Run with `just sounds` in mac/, or `bb scripts/sounds.bb`.
(ns sounds
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream FileOutputStream]
           [java.util Random]))

(def rate 44100)
(def afconvert "/usr/bin/afconvert")
(def mac-dir (-> *file* fs/absolutize fs/parent fs/parent))
(def out-dir (fs/path mac-dir "AgentMail" "Resources" "Sounds"))
;; Every sound peaks at -6 dBFS.
(def peak (Math/pow 10 (/ -6 20.0)))
(def two-pi (* 2 Math/PI))

(defn fail [& msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit 1))

;; Building blocks: t is the time in seconds from the start of the sound.

(defn sine [f t] (Math/sin (* two-pi f t)))

(defn rise
  "0 before u = 0, then a raised-cosine rise to 1 over len seconds."
  [u len]
  (cond (<= u 0) 0.0
        (>= u len) 1.0
        :else (* 0.5 (- 1 (Math/cos (/ (* Math/PI u) len))))))

(defn strike
  "A struck envelope from `at`: a raised-cosine rise over `attack` seconds
  (5 ms unless given), then an exponential decay with time constant `decay`."
  ([t at decay] (strike t at decay 0.005))
  ([t at decay attack]
   (let [u (- t at)]
     (* (rise u attack) (Math/exp (- (/ (max 0.0 (- u attack)) decay)))))))

(defn gate
  "A rounded note from `at`, len seconds long: a 5 ms rise, a slow decay,
  and a 20 ms raised-cosine release that ends at len."
  [t at len]
  (let [u (- t at)]
    (* (rise u 0.005) (rise (- len u) 0.02) (Math/exp (- (/ (max 0.0 u) 0.15))))))

;; The sounds, after the app icon: an envelope whose flap is a terminal
;; prompt with a block cursor.

(defn envelope
  "A letter arriving: a soft low thump, a sine gliding from 150 Hz down to
  110 Hz (with a quiet octave, so laptop speakers carry it) gone in about
  80 ms, then a two-note chime, E6 and G#6, each a sine with a touch of its
  2nd harmonic ringing for about 200 ms."
  [t]
  (let [glide 0.03
        phase (+ (* 110 t) (* 40 glide (- 1 (Math/exp (- (/ t glide))))))
        thump (* 0.8 (strike t 0 0.022)
                 (+ (Math/sin (* two-pi phase)) (* 0.25 (Math/sin (* 2 two-pi phase)))))
        note (fn [f at]
               (* 0.7 (strike t at 0.075)
                  (+ (sine f t) (* 0.12 (strike t at 0.03) (sine (* 2 f) t)))))]
    (+ thump (note 1318.51 0.14) (note 1661.22 0.30))))

(defn prompt-tone
  "Triangle-like: a sine with quiet odd harmonics."
  [f t]
  (+ (sine f t) (* -0.09 (sine (* 3 f) t)) (* 0.03 (sine (* 5 f) t))))

(defn prompt
  "A terminal ready for input: two rounded blips rising a fifth, 880 Hz then
  1320 Hz, 70 ms each with 40 ms between them; the second rings on as a
  quiet sine tail."
  [t]
  (+ (* (gate t 0 0.07) (prompt-tone 880 t))
     (* 0.9 (gate t 0.11 0.07) (prompt-tone 1320 t))
     (* 0.2 (strike t 0.11 0.12 0.03) (sine 1320 t))))

(defn noise
  "n samples of seeded white noise band-passed to about 0.8-5 kHz by two
  one-pole filters: the same every run."
  [n]
  (let [rng (Random. 7)
        coef #(- 1 (Math/exp (/ (- (* two-pi %)) rate)))
        lo (coef 5000)
        hi (coef 800)
        out (double-array n)]
    (loop [i 0 smooth 0.0 rumble 0.0]
      (when (< i n)
        (let [smooth (+ smooth (* lo (- (- (* 2 (.nextDouble rng)) 1) smooth)))
              rumble (+ rumble (* hi (- smooth rumble)))]
          (aset out i (- smooth rumble))
          (recur (inc i) smooth rumble))))
    out))

(defn cursor
  "A block cursor blinking at you: three ticks 120 ms apart, each a 2 kHz
  sine burst of about 15 ms over a soft noise transient; the last one is
  louder by about 3 dB."
  [seconds]
  (let [hiss (noise (inc (long (* seconds rate))))
        tick (fn [t at gain]
               (* gain (+ (* (strike t at 0.004) (sine 2000 t))
                          (* 0.6 (strike t at 0.002 0.0015) (aget hiss (int (Math/round (* t rate))))))))]
    (fn [t] (+ (tick t 0.01 0.7) (tick t 0.13 0.7) (tick t 0.25 1.0)))))

;; Rendering

(defn render
  "Samples f at the rate for seconds; then a 5 ms fade-in and a 15 ms
  fade-out at the edges, and a scale to the peak."
  [seconds f]
  (let [n (long (Math/round (* seconds rate)))
        edge #(rise (/ % (double rate)) %2)
        xs (double-array n)]
    (dotimes [i n]
      (aset xs i (* (double (f (/ i (double rate)))) (edge i 0.005) (edge (- n 1 i) 0.015))))
    (let [top (areduce xs i m 0.0 (max m (Math/abs (aget xs i))))
          k (/ peak top)]
      (dotimes [i n] (aset xs i (* k (aget xs i))))
      xs)))

(defn wav-bytes
  "A RIFF WAVE file of 16-bit mono PCM at the rate, from samples in [-1, 1]."
  [xs]
  (let [n (alength xs)
        size (* 2 n)
        out (ByteArrayOutputStream. (+ 44 size))
        u16 (fn [v] (.write out (int (bit-and v 0xff))) (.write out (int (bit-and (bit-shift-right v 8) 0xff))))
        u32 (fn [v] (u16 (bit-and v 0xffff)) (u16 (bit-shift-right v 16)))
        ascii (fn [s] (.writeBytes out (.getBytes s "US-ASCII")))]
    (ascii "RIFF") (u32 (+ 36 size)) (ascii "WAVE")
    (ascii "fmt ") (u32 16) (u16 1) (u16 1) (u32 rate) (u32 (* 2 rate)) (u16 2) (u16 16)
    (ascii "data") (u32 size)
    (dotimes [i n]
      (u16 (bit-and (Math/round (* 32767.0 (aget xs i))) 0xffff)))
    (.toByteArray out)))

(def sounds
  "Name (the file name and what Settings shows), length in seconds, sound."
  [["Envelope" 0.6 envelope]
   ["Prompt" 0.5 prompt]
   ["Cursor" 0.35 (cursor 0.35)]])

(defn -main []
  (when-not (fs/executable? afconvert)
    (fail "no" afconvert "(macOS only)"))
  (fs/create-dirs out-dir)
  (fs/with-temp-dir [tmp {:prefix "agent-mail-sounds-"}]
    (doseq [[sound-name seconds f] sounds]
      (let [wav (str (fs/path tmp (str sound-name ".wav")))
            caf (str (fs/path out-dir (str sound-name ".caf")))]
        (with-open [o (FileOutputStream. wav)]
          (.write o (wav-bytes (render seconds f))))
        (let [r (p/shell {:out :string :err :string :continue true}
                         afconvert "-f" "caff" "-d" "LEI16@44100" wav caf)]
          (when-not (zero? (:exit r))
            (fail "afconvert failed for" sound-name "\n" (str/trim (str (:out r) (:err r))))))
        (println (format "wrote %s (%.2f s)" (fs/relativize mac-dir caf) (double seconds)))))))

(-main)
