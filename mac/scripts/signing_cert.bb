#!/usr/bin/env bb
;; signing_cert.bb: creates the self-signed code-signing identity "Agent Mail
;; Local" in the login keychain, once. `just install` signs every build with
;; it, so the app's designated requirement stays the same across rebuilds and
;; macOS keeps its permissions (an ad hoc signature changes with every build;
;; Apple TN3127). Run with `just signing-cert` in mac/; trusting the
;; certificate asks for your login password. `--dry-run` only makes the
;; certificate and the .p12 in a temporary directory and checks them.
(ns signing-cert
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def identity-name "Agent Mail Local")
(def keychain (str (fs/path (fs/home) "Library" "Keychains" "login.keychain-db")))

(defn sh! [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when-not (zero? (:exit r))
      (binding [*out* *err*]
        (println "failed:" (str/join " " args))
        (println (str/trim (str (:out r) (:err r)))))
      (System/exit 1))
    (:out r)))

(defn identity? []
  (str/includes? (:out (p/shell {:out :string :err :string :continue true}
                                "security" "find-identity" "-v" "-p" "codesigning"))
                 (str "\"" identity-name "\"")))

(defn openssl
  "OpenSSL from Homebrew when present; /usr/bin/openssl is LibreSSL."
  []
  (or (some #(when (fs/executable? %) (str %))
            ["/opt/homebrew/opt/openssl@3/bin/openssl" "/opt/homebrew/bin/openssl" "/usr/local/bin/openssl"])
      "/usr/bin/openssl"))

(defn legacy-flag
  "OpenSSL 3 and later write .p12 files that `security import` can't read
   unless told to use the legacy algorithms; LibreSSL has no such flag."
  [ossl]
  (let [v (str/trim (sh! ossl "version"))]
    (if-let [[_ major] (re-find #"^OpenSSL (\d+)" v)]
      (if (>= (parse-long major) 3) ["-legacy"] [])
      [])))

(def extensions
  (str "[req]\ndistinguished_name = dn\nprompt = no\nx509_extensions = ext\n"
       "[dn]\nCN = " identity-name "\n"
       "[ext]\nbasicConstraints = critical, CA:false\nkeyUsage = critical, digitalSignature\n"
       "extendedKeyUsage = critical, codeSigning\nsubjectKeyIdentifier = hash\n"))

(defn make-p12!
  "Key, certificate and .p12 in `dir`; returns [cert p12 password]."
  [dir]
  (let [ossl (openssl)
        conf (str (fs/path dir "cert.conf"))
        key (str (fs/path dir "key.pem"))
        cert (str (fs/path dir "cert.pem"))
        p12 (str (fs/path dir "identity.p12"))
        pass (str (random-uuid))
        legacy (legacy-flag ossl)]
    (spit conf extensions)
    (sh! ossl "req" "-x509" "-newkey" "rsa:2048" "-nodes" "-days" "3650"
          "-keyout" key "-out" cert "-config" conf)
    (apply sh! (concat [ossl "pkcs12" "-export"] legacy
                        ["-inkey" key "-in" cert "-out" p12 "-name" identity-name "-passout" (str "pass:" pass)]))
    (println (str/trim (sh! ossl "x509" "-in" cert "-noout" "-subject" "-ext" "extendedKeyUsage,keyUsage")))
    (apply sh! (concat [ossl "pkcs12" "-in" p12 "-noout" "-passin" (str "pass:" pass)] legacy))
    (println "p12 ok" (if (seq legacy) "(legacy algorithms)" ""))
    [cert p12 pass]))

(defn -main [args]
  (let [dry? (some #{"--dry-run"} args)]
    (when (and (not dry?) (identity?))
      (println "already in the keychain:" identity-name)
      (System/exit 0))
    (fs/with-temp-dir [dir {:prefix "agent-mail-cert-"}]
      (let [[cert p12 pass] (make-p12! dir)]
        (if dry?
          (println "dry run: nothing imported")
          (do (sh! "security" "import" p12 "-k" keychain "-P" pass "-T" "/usr/bin/codesign")
              (println "imported; now trusting it for code signing (macOS asks for your login password)")
              (sh! "security" "add-trusted-cert" "-r" "trustRoot" "-p" "codeSign" "-k" keychain cert)))))
    (when-not dry?
      (if (identity?)
        (println "ready:" identity-name "(security find-identity -v -p codesigning)")
        (do (println "imported, but not listed as a valid code-signing identity; check its trust in Keychain Access")
            (System/exit 1))))))

(-main *command-line-args*)
