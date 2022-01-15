(ns clojure.core.typed.test.io
  (:require [clojure.core.typed :as t] 
            [clojure.test :refer :all]                
            [typed.clj.checker.test-utils :refer :all]))

(deftest delete-file-test
  (is-tc-e #(delete-file "abc") [-> Any]
           :requires [[clojure.java.io :refer [delete-file]]])
  (is-tc-e #(delete-file "abc" true) [-> Any]
           :requires [[clojure.java.io :refer [delete-file]]])
  (is-tc-err #(delete-file "abc") String
             :requires [[clojure.java.io :refer [delete-file]]]))
