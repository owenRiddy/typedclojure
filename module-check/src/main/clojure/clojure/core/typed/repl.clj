(ns ^:no-wiki clojure.core.typed.repl
  (:require [clojure.tools.nrepl.middleware :as mid]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]
            [clojure.tools.nrepl.middleware.interruptible-eval :as ev]
            [clojure.core.typed :as t]
            [clojure.core.typed.errors :as err]
            [clojure.tools.namespace.parse :as ns]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed.ns-deps-utils :as ns-utils]
            [clojure.core.typed.load :as load]
            [clojure.tools.analyzer.passes.jvm.emit-form :as emit-form]
            [clojure.core.typed.analyze-clj :as ana-clj]
            [clojure.tools.reader :as rd]
            [clojure.tools.reader.reader-types :as readers]
            [clojure.main :as main])
  (:import java.io.Writer))

(def install-typed-load
  (delay (alter-var-root #'load (constantly load/typed-load))))

(defn typed-ns-form? [current-ns rcode]
  (and (coll? rcode) 
       (= (first rcode) 'ns)
       (= #'ns (when current-ns
                 (ns-resolve current-ns 'ns)))
       (ns-utils/ns-has-core-typed-metadata? rcode)))

(defn handle-eval [handler {:keys [transport session code op] :as msg}]
  (let [;original-ns (@session #'*ns*)
        maybe-explicit-ns (when-let [ns (some-> (:ns msg) symbol find-ns)]
                            {#'*ns* ns})
        _ (assert (if (:ns msg)
                    (get maybe-explicit-ns #'*ns*)
                    true)
                  (str "Namespace " (:ns msg)
                       " does not exist"))
        _ (when maybe-explicit-ns
            (swap! session merge maybe-explicit-ns))
        flush (fn []
                (.flush ^Writer (@session #'*out*))
                (.flush ^Writer (@session #'*err*)))
        current-ns (@session #'*ns*)
        _ (assert (instance? clojure.lang.Namespace current-ns))
        typed? (boolean
                 (some-> current-ns meta :core.typed))
        rfail? (atom false)
        rcode (try (read-string msg)
                   (catch Throwable e
                     (reset! rfail? true)
                     nil))
        should-check? (and (or typed? (typed-ns-form? current-ns rcode))
                           (not @rfail?))]
    ;(prn "code" code)
    ;(prn "current-ns" current-ns)
    ;(prn "ns-msg" (:ns msg))
    ;(prn "msg" msg)
    ;(prn "should-check?" should-check?)
    ;(prn "rcode" rcode)
    (cond 
      should-check?
      (binding [*out* (@session #'*out*)
                *err* (@session #'*err*)]
        (t/load-if-needed)
        (impl/with-clojure-impl
          (let [{:keys [ret result ex]}
                (t/check-form-info rcode
                                   :eval-out-ast (partial ana-clj/eval-ast {})
                                   :bindings-atom session)]
            (if ex
              ;; ** throws exception, jumps to catch clause **
              (let [root-ex (#'clojure.main/root-cause ex)]
                (if-not (instance? ThreadDeath root-ex)
                  (do
                    (flush)
                    (swap! session assoc #'*e ex)
                    (transport/send transport 
                                    (misc/response-for msg {:status :eval-error
                                                            :ex (-> ex class str)
                                                            :root-ex (-> root-ex class str)}))
                    (main/repl-caught ex))
                  (transport/send transport (misc/response-for msg
                                                               {:status :done}))))
              (do
                (swap! session assoc
                       #'*3 (@session #'*2)
                       #'*2 (@session #'*1)
                       #'*1 result)
                (prn :- (:t ret))
                (flush)
                (transport/send transport 
                                (misc/response-for msg {:value (pr-str result)
                                                        :ns (-> (@session #'*ns*) ns-name str)}))
                (transport/send transport 
                                (misc/response-for msg {:status :done})))))))
      :else (handler msg))))

(defn handle-load-file [handler {:keys [file file-name file-path session transport] :as msg}]
  {:pre [session transport]}
  (let [rfail? (atom false)
        rcode (try (read-string file)
                   (catch Throwable e
                     (reset! rfail? true)
                     nil))
        should-check? (and (typed-ns-form? 'user rcode)
                           (not @rfail?))]
    ;(prn "rcode" rcode)
    ;(prn "should-check?" should-check?)
    (cond
      should-check?
      (binding [*out* (@session #'*out*)
                *err* (@session #'*err*)]
        (t/load-if-needed)
        (impl/with-clojure-impl
          (let [eof (Object.)
                rdr (readers/indexing-push-back-reader file 1 file-path)]
            (binding [;*ns* *ns*
                      *file* file-path
                      *source-path* file-name]
              (loop []
                (let [rcode (rd/read rdr false eof)]
                  ;(prn rcode)
                  (if (not= eof rcode)
                    (do
                      ;(prn "before" rcode (@session #'*ns*))
                      (let [{:keys [ret result ex]}
                            (t/check-form-info rcode
                                               :eval-out-ast (partial ana-clj/eval-ast {})
                                               :bindings-atom session)]
                        (if ex
                          ;; ** throws exception, jumps to catch clause **
                          (let [root-ex (#'clojure.main/root-cause ex)]
                            (if-not (instance? ThreadDeath root-ex)
                              (do
                                (swap! session assoc #'*e ex)
                                (transport/send transport 
                                                (misc/response-for msg {:status :done
                                                                        :ex (-> ex class str)
                                                                        :root-ex (-> root-ex class str)}))
                                (main/repl-caught ex))
                              (transport/send transport (misc/response-for msg
                                                                           {:status :done}))))
                          (recur))))
                    (transport/send transport 
                                    (misc/response-for msg {:status :done
                                                            :value "nil"})))))))))
      :else (handler msg))))

(defn wrap-clj-repl [handler]
  @install-typed-load
  (fn [{:keys [transport session op] :as msg}]
    (cond 
      (= "load-file" op) (handle-load-file handler msg)
      (= "eval" op)      (handle-eval handler msg)
      :else (handler msg))))

(mid/set-descriptor! #'wrap-clj-repl
  {:requires #{"clone"}
   :expects #{"eval" "load-file"}
   :handles {}})
