;;   Copyright (c) Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (https://opensource.org/license/epl-1-0/)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns typed.cljc.checker.check.throw
  (:require [typed.cljc.checker.type-rep :as r]
            [typed.cljc.checker.check-below :as below]
            [typed.cljc.checker.filter-ops :as fo]
            [typed.cljc.checker.type-ctors :as c]
            [typed.cljc.checker.filter-rep :as fl]
            [typed.cljc.checker.object-rep :as obj]
            [typed.cljc.checker.utils :as u]))

(defn check-throw
  [check {:keys [exception] :as expr} expected exception-expected]
  {:pre [((some-fn nil? r/TCResult?) exception-expected)]}
  (let [cexception (check exception exception-expected)
        ret (below/maybe-check-below
              (r/ret (c/Un)
                     ;never returns normally
                     (fo/-unreachable-filter)
                     obj/-empty)
              expected)]
    (assoc expr
           :exception cexception
           u/expr-type ret)))
