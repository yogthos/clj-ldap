(ns clj-ldap.test.client
  "Automated tests for clj-ldap"
  (:require [clj-ldap.client :as ldap]
            [clj-ldap.test.server :as server])
  (:use clojure.test))


;; Tests are run over a variety of connection types (LDAP and LDAPS for now)
(def ^:dynamic *connections* nil)
(def ^:dynamic *conn* nil)
(def ^:dynamic *c* nil)

;; Tests concentrate on a single object class
(def toplevel* "dc=alienscience,dc=org,dc=uk")
(def base* (str "ou=people," toplevel*))
(def dn*  (str "cn=%s," base*))
(def object-class* #{"top" "person"})

;; Variable to catch side effects
(def ^:dynamic *side-effects* nil)

;; Result of a successful write
(def success*      {:code 0 :name "success"})

(defn read-bytes-from-file
  [filename]
  (let [f (java.io.File. filename)
        ary (byte-array (.length f))
        is (java.io.FileInputStream. f)]
    (.read is ary)
    (.close is)
    ary))

;; People to test with
(def person-a*
     {:dn (format dn* "testa")
      :object {:objectClass object-class*
               :cn "testa"
               :sn "a"
               :description "description a"
               :telephoneNumber "000000001"
               :userPassword "passa"}})

(def person-b*
     {:dn (format dn* "testb")
      :object {:objectClass object-class*
               :cn "testb"
               :sn "b"
               :description "István Orosz"
               :telephoneNumber ["000000002" "00000003"]
               :userPassword "passb"}})

(def person-c*
     {:dn (format dn* "André Marchand")
      :object {:objectClass object-class*
               :cn "André Marchand"
               :sn "Marchand"
               :description "description c"
               :telephoneNumber "000000004"
               :userPassword "passc"}})

(defn- connect-to-server
  "Opens a sequence of connection pools on the localhost server with the
   given ports"
  [port ssl-port]
  [
   (ldap/connect {:host {:port port}})
   (ldap/connect {:host {:address "localhost"
                         :port port}
                  :num-connections 4})
   (ldap/connect {:host (str "localhost:" port)})
   (ldap/connect {:ssl? true
                  :host {:port ssl-port}
                  :initial-connections 2})
   (ldap/connect {:starTLS? true
                  :host {:port port}})
   (ldap/connect {:host {:port port}
                  :connect-timeout 1000
                  :timeout 5000
                  :max-connections 2})
   (ldap/connect {:host [(str "localhost:" port)
                         {:port ssl-port}]})
   (ldap/connect {:host [(str "localhost:" ssl-port)
                         {:port ssl-port}]
                  :ssl? true
                  :num-connections 5
                  :max-connections 10})])


(defn- test-server
  "Setup server"
  [f]
  (server/start!)
  (binding [*connections* (connect-to-server (server/ldapPort) (server/ldapsPort))]
    (f))
  (server/stop!))

(defn- add-toplevel-objects!
  "Adds top level entries, needed for testing, to the ldap server"
  [connection]
  (ldap/add connection toplevel*
            {:objectClass ["top" "domain" "extensibleObject"]
             :dc "alienscience"})
  (ldap/add connection base*
            {:objectClass ["top" "organizationalUnit"]
             :ou "people"})
  (ldap/add connection
            (str "cn=Saul Hazledine," base*)
            {:objectClass ["top" "Person"]
             :cn "Saul Hazledine"
             :sn "Hazledine"
             :description "Creator of bugs"}))

(defn- test-data
  "Provide test data"
  [f]
  (doseq [connection *connections*]
    (binding [*conn* connection]
      (try
        (add-toplevel-objects! *conn*)
        (ldap/add *conn* (:dn person-a*) (:object person-a*))
        (ldap/add *conn* (:dn person-b*) (:object person-b*))
        (catch Exception e))
      (f)
      (try
        (ldap/delete *conn* toplevel* {:delete-subtree true})
        (catch Exception e)))))

(use-fixtures :each test-data)
(use-fixtures :once test-server)

(deftest test-get
  (is (= (ldap/get *conn* (:dn person-a*))
         (assoc (:object person-a*) :dn (:dn person-a*))))
  (is (= (ldap/get *conn* (:dn person-b*))
         (assoc (:object person-b*) :dn (:dn person-b*))))
  (is (= (ldap/get *conn* (:dn person-a*) [:cn :sn])
         {:dn (:dn person-a*)
          :cn (-> person-a* :object :cn)
          :sn (-> person-a* :object :sn)})))

(deftest test-bind
  (if (> (-> *conn*
             (.getConnectionPoolStatistics)
             (.getMaximumAvailableConnections)) 1)
    (binding [*c* (ldap/get-connection *conn*)]
      (let [before (ldap/who-am-i *c*)
            _ (ldap/bind? *c* (:dn person-a*) "passa")
            a (ldap/who-am-i *c*)
            _ (ldap/release-connection *conn* *c*)]
        (is (= [before a])
           ["" (:dn person-a*)])))))

(deftest test-add-delete
  (is (= (ldap/add *conn* (:dn person-c*) (:object person-c*))
         success*))
  (is (= (ldap/get *conn* (:dn person-c*))
         (assoc (:object person-c*) :dn (:dn person-c*))))
  (is (= (ldap/delete *conn* (:dn person-c*))
         success*))
  (is (nil? (ldap/get *conn* (:dn person-c*))))
  (is (= (ldap/add *conn* (str "changeNumber=1234," base*)
                   {:objectClass ["changeLogEntry"]
                    :changeNumber 1234
                    :targetDN base*
                    :changeType "modify"})
         success*))
  (is (= (:changeNumber (ldap/get *conn* (str "changeNumber=1234," base*)))
         "1234"))
  (is (= (ldap/delete *conn* (str "changeNumber=1234," base*)
                      {:pre-read [:objectClass]})
         {:code 0, :name "success",
          :pre-read {:objectClass #{"top" "changeLogEntry"}}})))

(deftest test-delete-subtree
  (is (= (ldap/add *conn* (:dn person-c*) (:object person-c*))
         success*))
  (is (= (ldap/delete *conn* base* {:delete-subtree true})
         success*))
  (is (nil? (ldap/get *conn* base*))))

(deftest test-modify-add
  (is (= (ldap/modify *conn* (:dn person-a*)
                      {:add {:objectClass "organizationalPerson"
                             :l "Hollywood"}
                       :pre-read #{:objectClass :l :cn}
                       :post-read #{:l :cn}})
         {:code 0, :name "success",
          :pre-read {:objectClass #{"top" "person"}, :cn "testa"},
          :post-read {:l "Hollywood", :cn "testa"}}))
  (is (= (ldap/modify
          *conn* (:dn person-b*)
          {:add {:telephoneNumber ["0000000005" "0000000006"]}})
         success*))
  (let [new-a (ldap/get *conn* (:dn person-a*))
        new-b (ldap/get *conn* (:dn person-b*))
        obj-a (:object person-a*)
        obj-b (:object person-b*)]
    (is  (= (:objectClass new-a)
            (conj (:objectClass obj-a) "organizationalPerson")))
    (is (= (:l new-a) "Hollywood"))
    (is (= (set (:telephoneNumber new-b))
           (set (concat (:telephoneNumber obj-b)
                        ["0000000005" "0000000006"]))))))

(deftest test-modify-delete
  (let [b-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:delete {:description :all}})
           success*))
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:delete {:telephoneNumber (first b-phonenums)}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (-> (:object person-a*)
               (dissoc :description)
               (assoc :dn (:dn person-a*)))))
    (is (= (ldap/get *conn* (:dn person-b*))
           (-> (:object person-b*)
               (assoc :telephoneNumber (second b-phonenums))
               (assoc :dn (:dn person-b*)))))))

(deftest test-modify-replace
  (let [new-phonenums (-> person-b* :object :telephoneNumber)
        certificate-data (read-bytes-from-file
                           "test-resources/cert.binary")]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:replace {:telephoneNumber new-phonenums}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (-> (:object person-a*)
               (assoc :telephoneNumber new-phonenums)
               (assoc :dn (:dn person-a*)))))
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:add {:objectclass ["inetOrgPerson"
                                             "organizationalPerson"]
                               :userCertificate certificate-data}}
                        {:proxied-auth (str "dn:" (:dn person-a*))})
           success*))
    (is (= (seq (:userCertificate
                  (first (ldap/search *conn* (:dn person-a*)
                                      {:scope :base
                                       :filter "(objectclass=inetorgperson)"
                                       :attributes [:userCertificate]
                                       :byte-valued [:userCertificate]}))))
           (seq certificate-data)))
    (is (= (seq (:userCertificate
                  (first (ldap/search *conn* (:dn person-a*)
                                      {:scope :base
                                       :byte-valued [:userCertificate]}))))
           (seq certificate-data)))
    (is (= (seq (:userCertificate (ldap/get *conn* (:dn person-a*)
                                            [:userCertificate]
                                            [:userCertificate])))
           (seq certificate-data)))))

(deftest test-modify-all
  (let [b (:object person-b*)
        b-phonenums (:telephoneNumber b)]
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:add {:telephoneNumber "0000000005"}
                         :delete {:telephoneNumber (second b-phonenums)}
                         :replace {:description "desc x"}})
           success*))
    (let [new-b (ldap/get *conn* (:dn person-b*))]
      (is (= (set (:telephoneNumber new-b))
             (set [(first b-phonenums) "0000000005"])))
      (is (= (:description new-b) "desc x")))))

(deftest test-search
  (is (= (set (map :cn
                   (ldap/search *conn* base* {:attributes [:cn]})))
         (set [nil "testa" "testb" "Saul Hazledine"])))
  (is (= (set (map :cn
                   (ldap/search *conn* base*
                                {:attributes [:cn]
                                 :filter     "cn=test*"
                                 :proxied-auth  (str "dn:" (:dn person-a*))})))
         (set ["testa" "testb"])))
  (is (= (map :cn
              (ldap/search *conn* base*
                           {:filter "cn=*"
                            :server-sort {:is-critical true
                                          :sort-keys [:cn :ascending]}}))
         '("Saul Hazledine" "testa" "testb")))
  (is (= (count (map :cn
                     (ldap/search *conn* base*
                                  {:attributes [:cn] :filter "cn=*"
                                   :size-limit 2})))
         2))
  (is (= (:description (map :cn
                            (ldap/search *conn* base*
                                        {:attributes [:cn]
                                         :filter "cn=István Orosz"
                                         :types-only true})))
         nil))
  (is (= (set (map :description
                   (ldap/search *conn* base*
                                {:filter "cn=testb" :types-only false})))
         (set ["István Orosz"])))
  (binding [*side-effects* #{}]
    (ldap/search! *conn* base* {:attributes [:cn :sn] :filter "cn=test*"}
                  (fn [x]
                    (set! *side-effects*
                          (conj *side-effects* (dissoc x :dn)))))
    (is (= *side-effects*
           (set [{:cn "testa" :sn "a"}
                 {:cn "testb" :sn "b"}])))))

(deftest test-search-all
  (let [options {:attributes [:cn] :page-size 2}
        lazy-results (ldap/search-all *conn* base* options)
        eager-results (ldap/search *conn* base* options)
        ->cn-set (comp set (partial map :cn))]
    (testing "Since search-all is lazy, not all results are fetched"
      (is (not (realized? lazy-results))))
    (testing "Lazy and eager search eventually produce the same results"
      (is (->cn-set lazy-results)
          (->cn-set eager-results)))))

(deftest test-compare?
  (is (= (ldap/compare? *conn* (:dn person-b*)
                        :description "István Orosz")
         true))
  (is (= (ldap/compare? *conn* (:dn person-a*)
                        :description "István Orosz"
                        {:proxied-auth (str "dn:" (:dn person-b*))})
         false)))
