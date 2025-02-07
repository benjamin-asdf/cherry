(ns tasks
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn munge* [s reserved]
  (let [s (str (munge s))]
    (if (contains? reserved s)
      (str s "$")
      s)))

(defn shadow-extra-config
  []
  (let [core-config (edn/read-string (slurp (io/resource "cherry/cljs.core.edn")))
        reserved (edn/read-string (slurp (io/resource "cherry/js_reserved.edn")))
        vars (:vars core-config)
        ks (map #(symbol (munge* % reserved)) vars)
        vs (map #(symbol "cljs.core" (str %)) vars)
        core-map (zipmap ks vs)
        core-map (assoc core-map 'goog_typeOf 'goog/typeOf)]
    {:modules
     {:cljs_core {:exports core-map}}}))

(def test-config
  '{:compiler-options {:load-tests true}
    :modules {:cherry_tests {:init-fn cherry.compiler-test/init
                             :depends-on #{:compiler}}}})

(defn shadow-extra-test-config []
  (merge-with
   merge
   (shadow-extra-config)
   test-config))

(defn build-cherry-npm-package []
  (fs/create-dirs ".work")
  (fs/delete-tree "lib")
  (fs/delete-tree ".shadow-cljs")
  (spit ".work/config-merge.edn" (shadow-extra-config))
  (shell "npx shadow-cljs --config-merge .work/config-merge.edn release cherry"))

(defn publish []
  (build-cherry-npm-package)
  (run! fs/delete (fs/glob "lib" "*.map"))
  (shell "esbuild index.js --bundle --minify --format=iife --global-name=CherryCljs --outfile=lib/cherry.umd.js")
  (shell "npm publish"))

(defn watch-cherry []
  (fs/delete-tree ".shadow-cljs/builds/clava/dev/ana/cherry")
  (fs/create-dirs ".work")
  (spit ".work/config-merge.edn" (shadow-extra-test-config))
  (shell "npx shadow-cljs --aliases :dev --config-merge .work/config-merge.edn watch cherry"))

(defn test-cherry []
  (fs/create-dirs ".work")
  (spit ".work/config-merge.edn" (shadow-extra-test-config))
  (shell "npm install")
  (shell "npx shadow-cljs --config-merge .work/config-merge.edn release cherry")
  (shell "node lib/cherry_tests.js"))

(defn bump-compiler-common []
  (let [{:keys [out]}
        (shell {:out :string
                :dir "../squint/compiler-common"} "git rev-parse HEAD")
        sha (str/trim out)
        deps (slurp "deps.edn")
        nodes ((requiring-resolve 'borkdude.rewrite-edn/parse-string) deps)
        nodes ((requiring-resolve 'borkdude.rewrite-edn/assoc-in) nodes [:deps 'io.github.squint-cljs/compiler-common :git/sha] sha)
        deps (str nodes)]
    (spit "deps.edn" deps)))
