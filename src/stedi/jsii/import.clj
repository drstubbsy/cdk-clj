(ns stedi.jsii.import
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [stedi.jsii.assembly :as assm]
            [stedi.jsii.fqn :as fqn]
            [stedi.jsii.impl :as impl]
            [stedi.jsii.spec :as spec]))

(defn- arg-lists
  [parameters]
  (reverse
    (into (list)
          (map (partial into []
                        (mapcat (fn [{:keys [variadic name]}]
                                  (if variadic
                                    (list '& (symbol name))
                                    (list (symbol name)))))))
          (assm/arities parameters))))

(defn- intern-initializer
  [impl-ns-sym class-sym c]
  (let [fqs
        (symbol
          (intern impl-ns-sym '-initializer
                  (fn [& args]
                    (impl/create c args))))
        parameters (-> (.-fqn c)
                       (assm/get-type)
                       (:initializer)
                       (:parameters))]
    (spec/load-spec fqs)
    (stest/instrument fqs)
    (intern impl-ns-sym
            (with-meta class-sym
              {:arglists (arg-lists parameters)})
            c)
    (spec/load-spec (symbol (name impl-ns-sym) (name class-sym)))))

(defn- intern-methods
  [target-ns-sym methods c]
  (doseq [{:keys [static] :as method} methods]
    (let [method-sym (-> method (:name) (symbol))
          parameters (:parameters method)
          fqs
          (symbol
            (intern target-ns-sym
                    (with-meta method-sym
                      {:arglists (arg-lists
                                   (concat (when-not static
                                             (list {:name "this"}))
                                           parameters))})
                    (fn [& args]
                      (impl/-invoke
                        (if static c (first args))
                        {:op   method-sym
                         :args (if static args (rest args))}))))]
      (spec/load-spec fqs)
      (stest/instrument fqs))))

(defn- intern-enum-members
  [target-ns-sym members c]
  (doseq [member members]
    (let [member-sym  (-> member (:name) (symbol))
          member-k    (keyword member-sym)
          enum-member (get c member-k)]
      (intern target-ns-sym member-sym enum-member))))

(defn import-fqn
  [fqn alias-sym]
  (let [target-ns-sym (fqn/fqn->ns-sym fqn)
        class-sym     (-> fqn (string/split #"\.") (last) (symbol))
        impl-ns-sym   (symbol (str target-ns-sym ".impl"))
        c             (impl/get-class fqn)

        {:keys [methods members]} (impl/get-type-info c)]
    (create-ns target-ns-sym)
    (create-ns impl-ns-sym)
    (ns-unmap impl-ns-sym class-sym)
    (intern-initializer impl-ns-sym class-sym c)
    (intern-methods target-ns-sym methods c)
    (intern-enum-members target-ns-sym members c)
    (alias alias-sym target-ns-sym)
    (ns-unmap *ns* alias-sym)
    (refer impl-ns-sym
           :only [class-sym]
           :rename {class-sym alias-sym})))