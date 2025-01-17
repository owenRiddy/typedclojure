;;   Copyright (c) Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (https://opensource.org/license/epl-1-0/)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns typed.clj.checker.check.field
  (:require [typed.cljc.checker.check-below :as below]
            [typed.cljc.checker.check.utils :as cu]
            [typed.cljc.checker.type-rep :as r]
            [typed.cljc.checker.utils :as u]
            [clojure.core.typed.errors :as err]
            [typed.clj.checker.check.type-hints :as type-hints]
            [clojure.core.typed.ast-utils :as ast-u]
            [typed.cljc.checker.type-ctors :as c]
            [clojure.core.typed.coerce-utils :as coerce]
            [typed.clj.checker.subtype :as sub]
            [typed.clj.checker.parse-unparse :as prs]
            [typed.cljc.checker.datatype-env :as dt-env]
            [clojure.repl :as repl]
            [clojure.core.typed.util-vars :as vs]
            [typed.clj.checker.field-override-env :as fld-override]))

(defn check-static-field
  [expr expected]
  {:pre [(#{:static-field} (:op expr))]
   :post [(-> % u/expr-type r/TCResult?)]}
  (binding [vs/*current-expr* expr]
    (let [field (cu/FieldExpr->Field expr)
          fsym (cu/FieldExpr->qualsym expr)
          ftype (or (some-> fsym fld-override/get-field-override)
                    (cu/Field->Type field))]
      (assoc expr
             u/expr-type (below/maybe-check-below
                           (r/ret ftype)
                           expected)))))

(defn check-instance-field
  [{target-class :class field-name :field :keys [instance] :as expr} expected]
  {:pre [(#{:instance-field} (:op expr))
         (-> instance u/expr-type r/TCResult?)]
   :post [(-> % u/expr-type r/TCResult?)]}
  (binding [vs/*current-expr* expr]
   (let [field (cu/FieldExpr->Field expr)]
    (if-not target-class
      ; I think target-class will never be false
      (err/tc-delayed-error (str "Call to instance field "
                               (symbol field-name)
                               " requires type hints."
                               (type-hints/suggest-type-hints 
                                 field-name 
                                 (-> instance u/expr-type r/ret-t)
                                 []))
                          :form (ast-u/emit-form-fn expr)
                          :return (assoc expr
                                         u/expr-type (cu/error-ret expected)))
      (let [_ (assert (class? target-class))
            fsym (symbol field-name)
            ; check that the hinted class at least matches the runtime class we expect
            _ (let [expr-ty (c/fully-resolve-type (-> instance u/expr-type r/ret-t))
                    cls (cond
                          (r/DataType? expr-ty) (coerce/symbol->Class (:the-class expr-ty))
                          (r/RClass? expr-ty) (coerce/symbol->Class (:the-class expr-ty)))]
                (when-not (and cls
                               ; in case target-class has been redefined
                               (sub/class-isa? cls (-> target-class coerce/Class->symbol coerce/symbol->Class)))
                  (err/tc-delayed-error (str "Instance field " fsym " expected "
                                           (pr-str target-class)
                                           ", actual " (pr-str (prs/unparse-type expr-ty)))
                                      :form (ast-u/emit-form-fn expr))))

            ; datatype fields are special
            result-t (if-let [override (when-let [dtp (dt-env/get-datatype (coerce/Class->symbol target-class))]
                                         (let [dt (if (r/Poly? dtp)
                                                    ;generate new names
                                                    (cu/unwrap-datatype dtp (repeatedly (:nbound dtp) gensym))
                                                    dtp)
                                               _ (assert ((some-fn r/DataType? r/Record?) dt))
                                               demunged (symbol (repl/demunge (str fsym)))]
                                           (-> (c/DataType-fields* dt) (get demunged))))]
                       override
                       ; if not a datatype field, convert as normal
                       (if field
                         (cu/Field->Type field)
                         (err/tc-delayed-error (str "Instance field " fsym " needs type hints")
                                             :form (ast-u/emit-form-fn expr)
                                             :return (r/TCError-maker))))] 
        (assoc expr
               u/expr-type (below/maybe-check-below
                             (r/ret result-t)
                             expected)))))))
