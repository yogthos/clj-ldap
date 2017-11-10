(ns clj-ldap.test.start-tls
  (:require [clojure.test :refer :all]
            [clj-ldap.client :as ldap]
            [clj-ldap.test.server :as server]
            [clj-ldap.test.client :as client])
  (:import (com.unboundid.ldap.sdk LDAPConnectionPool)))

(defn- test-server
  "Setup server"
  [f]
  (server/start!)
  (f)
  (server/stop!))

(defn- test-data
  "Provide test data"
  [f]
  (try
    (let [conn (ldap/connect {:host {:port (server/ldapPort)}})]
      (#'client/add-toplevel-objects! conn)
      (ldap/add conn (:dn client/person-a*) (:object client/person-a*)))
    (catch Exception e))
  (f))

(use-fixtures :once test-server)
(use-fixtures :each test-data)

(deftest start-tls-test
  (testing "Connect with :bind-dn and StartTLS returns a valid connection pool"
    (is (= LDAPConnectionPool
           (type (ldap/connect {:host      {:address "localhost"
                                            :port    (server/ldapPort)}
                                :startTLS? true
                                :bind-dn   (str "cn=testa," client/base*)
                                :password  "passa"}))))))