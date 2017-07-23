(ns fulcro.gettext
  "A set of functions for working with GNU gettext translation files, including translation generation tools to go
  from PO files to cljc."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.java.shell :refer [sh]])
  (:import (java.io File)))

(defn- cljc-output-dir
  "Given a base source path (no trailing /) and a ns, returns the path to the directory that should contain it."
  [src-base ns]
  (let [path-from-ns (-> ns (str/replace #"\." "/") (str/replace #"-" "_"))]
    (str src-base "/" path-from-ns)))

(defn strip-comments
  "Given a sequence of strings that possibly contain comments of the form # ... <newline>: Return the sequence of
  strings without those comments."
  [string]
  (str/replace string #"(?m)^#[^\n\r]*(\r\n|\n|\r|$)" ""))

(defn is-header? [entry]
  (str/starts-with? entry "msgid \"\"\nmsgstr \"\"\n\"Project-Id-V"))

(defn get-blocks
  [resource]
  (filter (comp not is-header?) (map strip-comments (str/split (slurp resource) #"\n\n"))))

(defn stripquotes [s]
  (-> s
    (str/replace #"^\"" "")
    (str/replace #"\"$" "")))

(defn block->translation
  [gettext-block]
  (let [lines (str/split-lines gettext-block)]
    (-> (reduce (fn [{:keys [msgid msgctxt msgstr section] :as acc} line]
                  (let [[_ k v :as keyline] (re-matches #"^(msgid|msgctxt|msgstr)\s+\"(.*)\"\s*$" line)]
                    (cond
                      (and line (.matches line "^\".*\"$")) (update acc section #(str % (stripquotes line)))
                      (and k (#{"msgid" "msgctxt" "msgstr"} k)) (-> acc
                                                                  (assoc :section (keyword k))
                                                                  (update (keyword k) #(str % v)))
                      :else (do
                              (println "Unexpected input -->" line "<--")
                              acc)))) {} lines)
      (dissoc :section))))

(defn- map-translations
  "Map translated strings to lookup keys.

  Parameters:
  * `resource` - A resource to read the po from.

  Returns a map of msgstr values to msgctxt|msgid string keys."
  [file-or-resource]
  (let [translations (map block->translation (get-blocks
                                               (if (string? file-or-resource)
                                                 (io/as-file file-or-resource)
                                                 file-or-resource)))]
    (reduce (fn [acc translation]
              (let [{:keys [msgctxt msgid msgstr] :or {msgctxt "" msgid "" msgstr ""}} translation
                    msg (if (and (-> msgstr .trim .isEmpty) (-> msgid .trim .isEmpty not))
                          (do
                            (println (str "WARNING: Message '" msgid "' is missing a translation! Using the default locale's message instead of an empty string."))
                            msgid)
                          msgstr)]
                (assoc acc (str msgctxt "|" msgid) msg)))
      {} translations)))

(defn- wrap-with-swap
  "Wrap a translation map with supporting clojurescript code

  Parameters:
  * `locale` - the locale which this translation targets
  * `translation` - a clojurescript map of translations
  * `ns` - the ns in which to load the translations

  Returns a string of clojurescript code."
  [& {:keys [locale translation ns]}]
  (let [trans-ns  (symbol (str ns "." locale))
        locale    (if (keyword? locale) locale (keyword locale))
        ns-decl   (pp/write (list 'ns trans-ns
                              (list :require 'fulcro.i18n)) :pretty false :stream nil)
        comment   ";; This file was generated by untangled's i18n leiningen plugin."
        trans-def (pp/write (list 'def 'translations translation) :stream nil)
        swap-decl (pp/write (list 'swap! 'fulcro.i18n/*loaded-translations* 'assoc locale 'translations) :stream nil)]
    (str/join "\n\n" [ns-decl comment trans-def swap-decl])))



(defn- po-path [{:keys [podir]} po-file] (.getAbsolutePath (new File podir po-file)))

(defn- find-po-files
  "Finds any existing po-files, and adds them to settings. Returns the new settings."
  [{:keys [podir] :as settings}]
  (assoc settings :existing-po-files (filter #(.endsWith % ".po") (str/split-lines (:out (sh "ls" (.getAbsolutePath podir)))))))

(defn- gettext-missing?
  "Checks for gettext. Returns settings if all OK, nil otherwise."
  [settings]
  (let [xgettext (:exit (sh "which" "xgettext"))
        msgmerge (:exit (sh "which" "msgmerge"))]
    (if (or (not= xgettext 0) (not= msgmerge 0))
      (do
        (println "Count not find xgettext or msgmerge on PATH")
        nil)
      settings)))

(defn- run
  "Run a shell command and logging the command and result."
  [& args]
  (println "Running: " (str/join " " args))
  (let [result (:exit (apply sh args))]
    (when (not= 0 result)
      (print "Command Failed: " (str/join " " args))
      (println result))))

(defn- clojure-ize-locale [po-filename]
  (-> po-filename
    (str/replace #"^([a-z]+_*[A-Z]*).po$" "$1")
    (str/replace #"_" "-")))

(defn- expand-settings
  "Adds defaults and some additional helpful config items"
  [{:keys [src ns po] :as settings}]
  (let [srcdir      ^File (some-> src (io/as-file))
        output-path (some-> src (cljc-output-dir ns))
        outdir      ^File (some-> output-path (io/as-file))
        podir       ^File (some-> po (io/as-file))]
    (merge settings
      {:messages-pot (some-> podir (File. "messages.pot") (.getAbsolutePath))
       :podir        podir
       :outdir       outdir
       :srcdir       srcdir
       :output-path  output-path})))

(defn- verify-source-folders
  "Verifies that the source folder (target of the translation cljc) and ..."
  [{:keys [^File srcdir ^File outdir] :as settings}]
  (cond
    (not (.exists srcdir)) (do
                             (println "The given source-folder does not exist")
                             nil)
    (not (.exists outdir)) (do (println "Making missing source folder " (.getAbsolutePath outdir))
                               (.mkdirs outdir)
                               settings)
    :else settings))

(defn- verify-po-folders
  "Verifies that po files can be generated. Returns settings if so, nil otherwise."
  [{:keys [^File podir] :as settings}]
  (cond
    (not (.exists podir)) (do
                            (println "Creating missing PO directory: " (.getAbsolutePath podir))
                            (.mkdirs podir)
                            settings)
    (not (.isDirectory podir)) (do
                                 (println "po-folder must be a directory.")
                                 nil)
    :else settings))

(defn extract-strings
  "Extract strings from a compiled js file (whitespace optimized) as a PO template. If existing translations exist
  then this function will auto-update those (using `msgmerge`) as well.

  Remember that you must first compile your application (*without* modules) and with `:whitespace` optimization to generate
  a single javascript file. The gettext tools know how to extract from Javascript, but not Clojurescript.

  Parameters:
  `:js-path` - The path to your generated javascript
  `:po` - The directory where your PO template and PO files should live. "
  [{:keys [js-path po] :as settings}]
  (when-let [{:keys [existing-po-files messages-pot]
              :as   settings} (some-> settings
                                expand-settings
                                gettext-missing?
                                verify-po-folders
                                find-po-files)]
    (println "Extracting strings")
    (run "xgettext" "--from-code=UTF-8" "--debug" "-k" "-ktr:1" "-ktrc:1c,2" "-ktrf:1" "-o" messages-pot js-path)
    (doseq [po (:existing-po-files settings)]
      (when (.exists (io/as-file (po-path settings po)))
        (println "Merging extracted PO template file to existing translations for " po)
        (run "msgmerge" "--force-po" "--no-wrap" "-U" (po-path settings po) messages-pot)))))

(defn deploy-translations
  "Scans for .po files and generates cljc for those translations in your app.

  The settings map should contain:
  :src - The source folder (base) of where to emit the files. Defaults to `src`
  :ns - The namespace where your translations will live. Defaults to `translations`.
  :po - The directory where you po files live. Defaults to `i18n`"
  [{:keys [src ns po] :or {src "src" ns "translations" po "i18n"} :as settings}]
  (let [{:keys [existing-po-files output-path outdir]
         :as   settings} (some-> settings
                           expand-settings
                           verify-po-folders
                           verify-source-folders
                           find-po-files)
        replace-hyphen #(str/replace % #"-" "_")
        locales        (map clojure-ize-locale existing-po-files)]
    (println "po path is: " po)
    (println "Output path is: " output-path)
    (doseq [po existing-po-files]
      (let [locale            (clojure-ize-locale po)
            translation-map   (map-translations (po-path settings po))
            cljc-translations (wrap-with-swap :ns ns :locale locale :translation translation-map)
            cljc-trans-path   (str output-path "/" (replace-hyphen locale) ".cljc")]
        (println "Writing " cljc-trans-path)
        (spit cljc-trans-path cljc-translations)))
    (println "Deployed translations.")))
