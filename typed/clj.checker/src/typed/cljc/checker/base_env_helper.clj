;;   Copyright (c) Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns typed.cljc.checker.base-env-helper
  (:refer-clojure :exclude [type])
  (:require [typed.cljc.checker.type-rep :as r]
            [typed.clj.checker.parse-unparse :as prs]
            [typed.cljc.checker.utils :as u]
            [clojure.core.typed.coerce-utils :as coerce]
            [clojure.core.typed.contract-utils :as con]
            [typed.cljc.checker.free-ops :as free-ops]
            [typed.cljc.checker.type-ctors :as c]
            [typed.cljc.checker.declared-kind-env :as decl-env]
            [typed.clj.checker.rclass-env :as rcls]
            [clojure.core.typed.current-impl :as impl]
            [clojure.pprint :as pprint]
            [clojure.core.typed.macros :as macros]))

(defmacro method-nonnilable-return-mappings [& args]
  (assert (even? (count args)))
  (let [this-nsym (ns-name *ns*)]
    `(impl/with-clojure-impl
       (let [ts# (partition-all 2 '~args)]
         (into {}
               (map
                 (fn [[s# t# :as kv#]]
                   (assert (and (symbol? s#)
                                (namespace s#))
                           "Need fully qualified symbol")
                   (macros/when-bindable-defining-ns '~this-nsym
                     [s# (env-utils/delay-type t#)])))
               ts#)))))

(defmacro method-nilable-param-mappings [& args]
  (assert (even? (count args)))
  (let [this-nsym (ns-name *ns*)]
    `(impl/with-clojure-impl
       (let [ts# (partition-all 2 '~args)]
         (into {}
               (map
                 (fn [[s# t# :as kv#]]
                   (assert (and (symbol? s#)
                                (namespace s#))
                           "Need fully qualified symbol")
                   (macros/when-bindable-defining-ns '~this-nsym
                     [s# (env-utils/delay-type t#)])))
               ts#)))))

(defmacro method-override-mappings [& args]
  (assert (even? (count args)))
  (let [this-nsym (ns-name *ns*)]
    `(impl/with-clojure-impl
       (let [ts# (partition-all 2 '~args)]
         (into {}
               (map
                 (fn [[s# t# :as kv#]]
                   (assert (and (symbol? s#)
                                (namespace s#))
                           "Need fully qualified symbol")
                   (macros/when-bindable-defining-ns '~this-nsym
                     [s# (env-utils/delay-type (prs/delay-parse-type t#))])))
               ts#)))))

(defmacro field-override-mappings [& args]
  (assert (even? (count args)))
  (let [this-nsym (ns-name *ns*)]
    `(impl/with-clojure-impl
       (let [ts# (partition-all 2 '~args)]
         (into {}
               (map
                 (fn [[s# t# :as kv#]]
                   (assert (and (symbol? s#)
                                (namespace s#))
                           "Need fully qualified symbol")
                   (macros/when-bindable-defining-ns '~this-nsym
                     [s# (env-utils/delay-type (prs/delay-parse-type t#))])))
               ts#)))))

(defmacro ctor-override-mappings [& args]
  (assert (even? (count args)))
  (let [this-nsym (ns-name *ns*)]
    `(impl/with-clojure-impl
       (let [ts# (partition-all 2 '~args)]
         (into {}
               (map
                 (fn [[s# t# :as kv#]]
                   (assert (and (symbol? s#)
                                (not (namespace s#)))
                           "Need unqualified symbol")
                   (macros/when-bindable-defining-ns '~this-nsym
                     [s# (env-utils/delay-type (prs/delay-parse-type t#))])))
               ts#)))))

;; Alter class

(defn build-replacement-syntax [m]
  (impl/with-clojure-impl
    (into {}
          (map
            (fn [[k v :as kv]]
              (assert (= 2 (count kv)) kv)
              [(if-let [c (ns-resolve (prs/parse-in-ns) k)]
                 (do (assert (class? c) (pr-str c))
                     (coerce/Class->symbol c))
                 (do (assert nil (str "Unknown rclass replacement: " k))
                     k))
               (prs/parse-type v)]))
          m)))

(defn resolve-class-symbol [the-class]
  (impl/with-clojure-impl
    (let [cls (when-let [c (ns-resolve (prs/parse-in-ns) the-class)]
                (when (class? c)
                  c))]
      (assert cls (str "Cannot resolve class " the-class))
      (coerce/Class->symbol cls))))

(defn make-RClass [the-class frees-syn opts]
  (impl/with-clojure-impl
    (let [{replacements-syn :replace
           unchecked-ancestors-syn :unchecked-ancestors} (if (map? opts)
                                                           opts
                                                           (apply hash-map opts))
          _ (when unchecked-ancestors-syn
              (assert (vector? unchecked-ancestors-syn)))
          {variances :variances
           nmes :nmes
           bnds :bnds}
          (when-some [fs (seq frees-syn)]
            ; don't bound frees because mutually dependent bounds are problematic
            (let [b (free-ops/with-free-symbols (mapv (fn [s]
                                                        {:pre [(vector? s)]
                                                         :post [(symbol? %)]}
                                                        (first s))
                                                      fs)
                      (mapv prs/parse-tfn-binder fs))]
              {:variances (map :variance b)
               :nmes (map :nme b)
               :bnds (map :bound b)}))
          frees (map r/make-F nmes)
          csym (resolve-class-symbol the-class)
          frees-and-bnds (zipmap frees bnds)]
      (assert ((con/hash-c? r/F? r/Bounds?) frees-and-bnds) frees-and-bnds)
      (c/RClass* nmes variances frees csym
                 (free-ops/with-bounded-frees frees-and-bnds
                   (build-replacement-syntax replacements-syn))
                 (free-ops/with-bounded-frees frees-and-bnds
                   (mapv prs/parse-type unchecked-ancestors-syn))
                 bnds))))

(defn declared-kind-for-rclass [frees]
  (let [fs (map first frees)
        _ (assert (every? symbol? fs) fs)
        vs (map (fn [[v & {:keys [variance]}]] variance) frees)]
    (c/TypeFn* fs vs (repeat (count vs) r/no-bounds) r/-any)))

(defn process-altered-class [[s [frees & opts] :as kv]]
  (assert (= 2 (count kv)) (print-str "Uneven args passed to `alters`:" kv))
  (impl/with-clojure-impl
    (let [sym (resolve-class-symbol s)
          decl-kind (declared-kind-for-rclass frees)
          _ (when (r/TypeFn? decl-kind)
              (decl-env/add-declared-kind sym decl-kind))
          ;;TODO implement reparsing
          rcls (make-RClass s frees opts)]
      ;accumulate altered classes in initial env
      (rcls/alter-class* sym rcls)
      (decl-env/remove-declared-kind sym)
      [sym rcls])))

(defmacro alters [& args]
  `(let [args# '~args
         _# (assert (even? (count args#)))
         ts# (partition-all 2 args#)]
     (into {} (map process-altered-class)
           ts#)))
