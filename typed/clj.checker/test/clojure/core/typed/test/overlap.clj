(ns clojure.core.typed.test.overlap
  (:require [clojure.test :refer :all]
            [typed.clj.checker.test-utils :refer :all]
            [typed.clojure :as t]
            [typed.cljc.checker.type-ctors :refer :all]
            [typed.cljc.checker.type-rep :refer :all]
            [typed.clj.checker.parse-unparse :refer [parse-type]]))

(defmacro overlap-prs [s1 s2]
  `(clj
     (overlap (parse-type ~s1) (parse-type ~s2))))

(deftest overlap-test
  (is-tc-e :load)
  (is-clj (not (overlap -false -true)))
  (is-clj (not (overlap (-val :a) (-val :b))))
  ;; classes overlap with their superclass
  (is-clj (overlap (RClass-of Number) (RClass-of Integer)))
  (is-clj (not (overlap (RClass-of Number) (RClass-of clojure.lang.Symbol))))
  ;; different classes are disjoint
  (is-clj (not (overlap (RClass-of clojure.lang.Keyword) (RClass-of clojure.lang.Symbol))))
  ;; different abstract and final classes are disjoint
  (is-clj (not (overlap (RClass-of Number) (RClass-of String))))
  ;; final classes are disjoint with any interfaces it doesn't implement
  (is-clj (not (overlap (RClass-of clojure.lang.ISeq [-any]) (RClass-of String))))
  ;; covariant parameter 
  (is-clj (overlap (RClass-of clojure.lang.ISeq [(RClass-of Number)])
                   (RClass-of clojure.lang.ISeq [(RClass-of String)])))
  ;; final classes overlap with interfaces it implements
  (is-clj (overlap (RClass-of clojure.lang.Atom [-any -any]) (RClass-of clojure.lang.IMeta)))
  ;; interfaces overlap
  (is-clj (overlap (RClass-of Number) (RClass-of CharSequence)))
  ;; different abstract class are disjoint
  (is-clj (not (overlap (RClass-of java.nio.Buffer) (RClass-of Number))))
  (is-clj (overlap (-name `t/Seqable -any) (RClass-of clojure.lang.IMeta)))
  (is-clj (overlap (-name `t/Seqable -any) (RClass-of clojure.lang.PersistentVector [-any]))))

(deftest hmap-overlap-test
  (is-clj
    (not (overlap-prs `t/Int `t/Kw)))
  (is-clj
    (not
      (overlap-prs
        `(t/HMap :mandatory {:a t/Int})
        `(t/HMap :mandatory {:a t/Kw}))))
  (is-clj
    (overlap-prs
      `(t/HMap :optional {:a t/Int})
      `(t/HMap :optional {:a t/Kw})))
  (is-clj
    (overlap-prs
      `(t/HMap :complete? true :optional {:a t/Int})
      `(t/HMap :complete? true :optional {:a t/Kw}))))

(deftest hvec-overlap-test
  (testing "without rest types"
    (testing "when the fixed types match"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num])
        `(t/HVec [t/Num]))))

    (testing "when the fixed types differ"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num])
         `(t/HVec [t/Str])))))

    (testing "with a differing number of fixed types"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num])
         `(t/HVec [t/Num t/Str]))))))

  (testing "with one rest type"
    (testing "when fixed types match"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num])
        `(t/HVec [t/Num t/Str ~'*]))))

    (testing "when fixed types differ"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num])
         `(t/HVec [t/Str t/Str ~'*])))))

    (testing "when the extra fixed types match the rest type"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num ~'*])
        `(t/HVec [t/Num]))))

    (testing "when the extra fixed types differ from the rest type"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num ~'*])
         `(t/HVec [t/Str])))))

    (testing "when the extra fixed types come from type with the rest type"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Str t/Str t/Str ~'*])
         `(t/HVec [t/Str]))))))

  (testing "with two rest types"
    (testing "when the rest types match"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num ~'*])
        `(t/HVec [t/Num ~'*]))))

    (testing "when the rest types differ"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num ~'*])
         `(t/HVec [t/Str ~'*])))))

    (testing "when the extra fixed types match the rest type of shorter"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num ~'*])
        `(t/HVec [t/Num t/Num ~'*]))))

    (testing "when the extra fixed types differ from the rest type of shorter"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num ~'*])
         `(t/HVec [t/Str t/Num ~'*])))))

    (testing "when the fixed types match"
      (is-clj
       (overlap-prs
        `(t/HVec [t/Num t/Str ~'*])
        `(t/HVec [t/Num t/Str ~'*]))))

    (testing "when the fixed types differ"
      (is-clj
       (not
        (overlap-prs
         `(t/HVec [t/Num t/Str ~'*])
         `(t/HVec [t/Str t/Str ~'*])))))))

(deftest hvec-complex-overlap
  (is-clj (overlap-prs `(t/HVec [t/Int t/Num])
                       `(t/HVec [t/Num t/Int]))))

(deftest overlap-free-test
  (is-clj (overlap (make-F 'a)
                   (-val 'a)))
  (is-clj (overlap (-val 'a)
                   (make-F 'a)))
  (is-clj (overlap (make-F 'b)
                   (make-F 'a))))

;; TODO add tests for trailing map arg :maybe-trailing-nilable-non-empty-map?
(deftest overlap-CountRange-KwArgsSeq-test
  ;; (I (KwArgsSeq :mandatory {:foo Any}) (CountRange 1))
  ;; simplifies to (KwArgsSeq :mandatory {:foo Any})--because
  ;; it already has (CountRange 2) implicitly--so types like
  ;; this must not overlap as it's equivalent to (I (CountRange 0 1) (CountRange 2))
  (is-clj (not
            (overlap
              (make-CountRange 0 1)
              (-kw-args-seq :mandatory {(-val :foo) -any}))))
  ;; optional entries only, so knows nothing about its count except it's
  ;; even. this needs to be manually handled since there are currently
  ;; no types representing even-counted collections.
  (is-clj (not
            (overlap
              (make-CountRange 1 1)
              (-kw-args-seq :optional {(-val :foo) -any})))))
