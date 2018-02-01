(ns clj-ldap.client
  "LDAP client"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as string]
            [clojure.pprint :refer (pprint)])
  (:import [com.unboundid.ldap.sdk
            LDAPResult
            LDAPConnectionOptions
            LDAPConnection
            ResultCode
            LDAPConnectionPool
            LDAPException
            Attribute
            Entry
            ModificationType
            ModifyRequest
            ModifyDNRequest
            Modification
            DeleteRequest
            SimpleBindRequest
            RoundRobinServerSet
            SearchRequest
            LDAPEntrySource
            EntrySourceException
            SearchScope
            DereferencePolicy
            LDAPSearchException
            Control
            StartTLSPostConnectProcessor
            CompareRequest
            CompareResult])
  (:import [com.unboundid.ldap.sdk.extensions
            PasswordModifyExtendedRequest
            PasswordModifyExtendedResult
            WhoAmIExtendedRequest
            WhoAmIExtendedResult
            StartTLSExtendedRequest])
  (:import [com.unboundid.ldap.sdk.controls
            PreReadRequestControl
            PostReadRequestControl
            PreReadResponseControl
            PostReadResponseControl
            ProxiedAuthorizationV2RequestControl
            SimplePagedResultsControl
            ServerSideSortRequestControl
            SortKey
            SubtreeDeleteRequestControl])
  (:import [com.unboundid.util
            Base64])
  (:import [com.unboundid.util.ssl
            SSLUtil
            TrustAllTrustManager
            TrustStoreTrustManager]))

;;======== Helper functions ====================================================

(def not-nil? (complement nil?))

;; Define get-value to return String or Bytes depending on boolean 'byte-value'
(defmulti ^:private get-value (fn [attr byte-value] byte-value))

(defmethod ^:private get-value true
  [attr _]
  (if (> (.size attr) 1)
    (vec (.getValueByteArrays attr))
    (.getValueByteArray attr)))

(defmethod ^:private get-value false
  [attr _]
  (if (> (.size attr) 1)
    (vec (.getValues attr))
    (.getValue attr)))

(defn- encodeBase64 [attr]
  "Can be used to produce LDIF"
  (if (> (.size attr) 1)
    (map #(Base64/encode %) (get-value attr))
    (Base64/encode (get-value attr))))

(defn- extract-attribute
  "Extracts [:name value] from the given attribute object. Converts
   the objectClass attribute to a set. The byte-valued collection is
   referenced to detemine whether to return a String or byte array"
  [attr byte-valued]
  (let [k (keyword (.getName attr))
        byte-value (contains? (set byte-valued) (keyword (.getName attr)))]
    (cond
      (= :objectClass k)     [k (set (get-value attr byte-value))]
      :else                  [k (get-value attr byte-value)])))

(defn- entry-as-map
  "Returns a closure which converts an Entry object into a map optionally
   adding the DN. We pass along the byte-valued collection to properly
   return binary data."
  ([byte-valued]
     (entry-as-map byte-valued true))
  ([byte-valued dn?]
   (fn [entry]
     (let [attrs (seq (.getAttributes entry))]
      (if dn?
        (apply hash-map :dn (.getDN entry)
               (mapcat #(extract-attribute % byte-valued) attrs))
        (apply hash-map
               (mapcat #(extract-attribute % byte-valued) attrs)))))))

(defn- add-response-control
  "Adds the values contained in given response control to the given map"
  [m control]
  (condp instance? control
    PreReadResponseControl
    (update-in m [:pre-read] merge ((entry-as-map [] false)
                                     (.getEntry control)))
    PostReadResponseControl
    (update-in m [:post-read] merge ((entry-as-map [] false)
                                      (.getEntry control)))
    m))

(defn- add-response-controls
  "Adds the values contained in the given response controls to the given map"
  [controls m]
  (reduce add-response-control m (seq controls)))

(defn- ldap-result
  "Converts an LDAPResult object into a map"
  [obj]
  (let [res (.getResultCode obj)
        controls (.getResponseControls obj)]
    (add-response-controls
     controls
     {:code (.intValue res)
      :name (.getName res)})))

(defn- connection-options
  "Returns a LDAPConnectionOptions object"
  [{:keys [connect-timeout timeout]}]
  (let [opt (LDAPConnectionOptions.)]
    (when connect-timeout (.setConnectTimeoutMillis opt connect-timeout))
    (when timeout         (.setResponseTimeoutMillis opt timeout))
    opt))

(defn- create-ssl-context
  "Returns a SSLContext object"
  [{:keys [trust-store]}]
  (let [trust-manager (if trust-store
                        (TrustStoreTrustManager. trust-store)
                        (TrustAllTrustManager.))
        ssl-util (SSLUtil. trust-manager)]
    (.createSSLContext ssl-util)))

(defn- create-ssl-factory
  "Returns a SSLSocketFactory object"
  [{:keys [trust-store]}]
  (let [trust-manager (if trust-store
                        (TrustStoreTrustManager. trust-store)
                        (TrustAllTrustManager.))
        ssl-util (SSLUtil. trust-manager)]
    (.createSSLSocketFactory ssl-util)))

(defn- host-as-map
  "Returns a single host as a map containing an :address and an optional
   :port"
  [host]
  (cond
    (nil? host)      {:address "localhost" :port 389}
    (string? host)   (let [[address port] (string/split host #":")]
                       {:address (if (= address "")
                                   "localhost"
                                   address)
                        :port (if port
                                (int (Integer. port)))})
    (map? host)      (merge {:address "localhost"} host)
    :else            (throw
                      (IllegalArgumentException.
                       (str "Invalid host for an ldap connection : "
                            host)))))

(defn- create-connection
  "Create an LDAPConnection object"
  [{:keys [host ssl? startTLS?]
    :as options
    :or {ssl? false startTLS? false}}]
  (let [h (host-as-map host)
        host (:address h)
        ldap-port (or (:port h) 389)
        ldaps-port (or (:port h) 636)
        opt (connection-options options)]
    (cond
      ssl? (let [ssl (create-ssl-factory options)]
             (LDAPConnection. ssl opt host ldaps-port))
      startTLS? (let [conn (LDAPConnection. opt host ldap-port)]
                  (.processExtendedOperation conn (StartTLSExtendedRequest. (create-ssl-context options)))
                  conn)
      :else (LDAPConnection. opt host ldap-port))))

(defn- bind-request
  "Returns a BindRequest object"
  [{:keys [bind-dn password]}]
  (if bind-dn
    (SimpleBindRequest. bind-dn password)
    (SimpleBindRequest.)))

(defn- create-server-set
  "Returns a RoundRobinServerSet"
  [{:keys [host ssl? startTLS?]
    :as options
    :or {ssl? false startTLS? false}}]
  (let [hosts (map host-as-map host)
        addresses (into-array (map :address hosts))
        opt (connection-options options)]
    (if ssl?
      (let [ssl (create-ssl-factory options)
            ports (int-array (map #(or (:port %) (int 636)) hosts))]
        (RoundRobinServerSet. addresses ports ssl opt))
      (let [ports (int-array (map #(or (:port %) (int 389)) hosts))]
        (RoundRobinServerSet. addresses ports opt)))))

(defn- connect-to-host
  "Connect to a single host"
  [{:keys [num-connections initial-connections max-connections startTLS?]
    :as options
    :or {num-connections 1 startTLS? false}}]
  (let [connection (create-connection options)
        bind-result (.bind connection (bind-request options))
        pcp (if startTLS?
              (StartTLSPostConnectProcessor. (create-ssl-context options))
              nil)
        initial-connections (or initial-connections num-connections)
        max-connections (or max-connections initial-connections)]
    (if (= ResultCode/SUCCESS (.getResultCode bind-result))
      (LDAPConnectionPool. connection initial-connections max-connections pcp)
      (throw (LDAPException. bind-result)))))

(defn- connect-to-hosts
  "Connects to multiple hosts"
  [{:keys [num-connections initial-connections max-connections startTLS?]
    :as options
    :or {num-connections 1 startTLS? false}}]
  (let [server-set (create-server-set options)
        bind-request (bind-request options)
        pcp (when startTLS?
              (StartTLSPostConnectProcessor. (create-ssl-context options)))
        initial-connections (or initial-connections num-connections)
        max-connections (or max-connections initial-connections)]
    (LDAPConnectionPool. server-set bind-request
                         initial-connections max-connections pcp)))


(defn- set-entry-kv!
  "Sets the given key/value pair in the given entry object"
  [entry-obj k v]
  (let [name-str (name k)]
    (.addAttribute entry-obj
                   (if (coll? v)
                     (Attribute. name-str (into-array v))
                     (Attribute. name-str (str v))))))

(defn- set-entry-map!
  "Sets the attributes in the given entry object using the given map"
  [entry-obj m]
  (doseq [[k v] m]
    (set-entry-kv! entry-obj k v)))

(defn- byte-array?
  [v]
  (= (type v) (type (byte-array 0))))

(defn- create-modification
  "Creates a modification object"
  [modify-op attribute values]
  (cond
    (and (coll? values) (byte-array? (first values)))
      (Modification. modify-op attribute (into-array values))
    (coll? values)
      (Modification. modify-op attribute (into-array String (map str values)))
    (= :all values)
      (Modification. modify-op attribute)
    (byte-array? values)
      (Modification. modify-op attribute values)
    :else
      (Modification. modify-op attribute (str values))))

(defn- modify-ops
  "Returns a sequence of Modification objects to do the given operation
   using the contents of the given map."
  [modify-op modify-map]
  (for [[k v] modify-map]
    (create-modification modify-op (name k) v)))

(defn- add-request-controls
  [request options]
  "Adds LDAP controls to the given request"
  (when (contains? options :pre-read)
    (let [attributes (map name (options :pre-read))
          pre-read-control (PreReadRequestControl. (into-array attributes))]
      (.addControl request pre-read-control)))
  (when (contains? options :post-read)
    (let [attributes (map name (options :post-read))
          pre-read-control (PostReadRequestControl. (into-array attributes))]
      (.addControl request pre-read-control)))
  (when (contains? options :proxied-auth)
    (.addControl request (ProxiedAuthorizationV2RequestControl.
                           (:proxied-auth options))))
  (when (and (contains? options :delete-subtree) (= (type request) DeleteRequest))
    (.addControl request (SubtreeDeleteRequestControl.))))

(defn- get-modify-request
  "Sets up a ModifyRequest object using the contents of the given map"
  [dn modifications]
  (let [adds (modify-ops ModificationType/ADD (modifications :add))
        deletes (modify-ops ModificationType/DELETE (modifications :delete))
        replacements (modify-ops ModificationType/REPLACE
                                 (modifications :replace))
        increments (modify-ops ModificationType/INCREMENT
                               (modifications :increment))
        all (concat adds deletes replacements increments)]
    (doto (ModifyRequest. dn (into-array all))
      (add-request-controls modifications))))

(defn- next-entry
  "Attempts to get the next entry from an LDAPEntrySource object"
  [source]
  (try
    (.nextEntry source)
    (catch EntrySourceException e
      (if (.mayContinueReading e)
        (.nextEntry source)
        (throw e)))))

(defn- entry-seq
  "Returns a lazy sequence of entries from an LDAPEntrySource object"
  [source]
  (if-let [n (.nextEntry source)]
    (cons n (lazy-seq (entry-seq source)))))

;; Extended version of search-results function using a
;; SearchRequest that uses a SimplePagedResultsControl.
;; Allows us to read arbitrarily large result sets.
(defn- search-all-results
  "Returns a lazy sequence of search results via paging so we don't run into
   size limits with the number of results."
  ([connection {:keys [base scope filter attributes size-limit time-limit
                       types-only controls byte-valued
                       cookie page-size request]
                :or {page-size 500}
                :as options}]
   (let [paging (SimplePagedResultsControl. page-size cookie)
         req (doto (or request
                     (cond-> (SearchRequest. base scope DereferencePolicy/NEVER
                                             size-limit time-limit types-only filter attributes)
                       (seq controls) (doto (.addControls (into-array Control controls)))))
               (.setControls (list paging)))
         response (.search connection req)
         results (->> response
                      .getSearchEntries
                      (map (entry-as-map byte-valued))
                      (remove empty?))
         next-paging (SimplePagedResultsControl/get response)
         next-cookie (.getCookie next-paging)]
     (if (and next-paging (pos? (.getValueLength next-cookie)))
       (search-all-results connection
                           (merge options {:cookie next-cookie :request request})
                           results)
       results)))
  ([connection options results]
   (lazy-seq (concat results (search-all-results connection options)))))

(defn- search-results
  "Returns a sequence of search results for the given search criteria.
   Ignore a size limit exceeded exception if one occurs. If the caller
   provided a respf then apply the function to any response controls."
  [conn {:keys [base scope filter attributes size-limit time-limit types-only
                controls respf byte-valued]}]
  (try
    (let [req (SearchRequest. base scope DereferencePolicy/NEVER size-limit
                              time-limit types-only filter attributes)
          - (and (not (empty? controls))
                 (.addControls req (into-array Control controls)))
          res (.search conn req)]
      (when (not-nil? respf) (respf (.getResponseControls res)))
      (if (> (.getEntryCount res) 0)
       (map (entry-as-map byte-valued) (.getSearchEntries res))))
    (catch LDAPSearchException e
      (when (not-nil? respf) (respf (.getResponseControls e)))
      (if (= ResultCode/SIZE_LIMIT_EXCEEDED (.getResultCode e))
        (map (entry-as-map byte-valued) (.getSearchEntries e))
        (throw e)))))

(defn- search-results!
  "Call the given function with the results of the search using
   the given search criteria"
  [pool {:keys [base scope filter attributes size-limit time-limit types-only
                controls respf byte-valued]} f]
  (let [req (SearchRequest. base scope DereferencePolicy/NEVER
                            size-limit time-limit types-only filter attributes)
        - (and (not (empty? controls))
               (.addControls req (into-array Control controls)))
        conn (.getConnection pool)]
    (try
      (with-open [source (LDAPEntrySource. conn req false)]
        (let [res (.getSearchResult source)]
          (when (not-nil? respf) (respf (.getResponseControls res)))
          (doseq [i (remove empty?
                           (map (entry-as-map byte-valued)
                                (entry-seq source)))]
           (f i))))
      (.releaseConnection pool conn)
      (catch EntrySourceException e
        (.releaseDefunctConnection pool conn)
        (throw e)))))

(defn- get-scope
  "Converts a keyword into a SearchScope object"
  [k]
  (condp = k
    :base SearchScope/BASE
    :one  SearchScope/ONE
    :subordinate SearchScope/SUBORDINATE_SUBTREE
    SearchScope/SUB))

(defn- get-attributes
  "Converts a collection of attributes into an array"
  [attrs]
  (cond
    (or (nil? attrs)
        (empty? attrs))    (into-array java.lang.String
                                       [SearchRequest/ALL_USER_ATTRIBUTES])
    :else                  (into-array java.lang.String
                                       (map name attrs))))

(defn- sortOrder
  "Convert friendly name to boolean according to ServerSideSort control"
  [order]
  (condp = order
        :ascending false
        :descending true
        :else false))

(defn- createServerSideSort
  "Create a ServerSideSortRequestControl base on the provided map with
  keys :is-critical and :sort-keys. The former is boolean valued while the latter
  is a vector: [:attr1 :ascending :attr2 :descending ... ].
  Throw an exception if no sortKey is defined. If :is-critical is not defined,
  assume false."
  [{:keys [is-critical sort-keys] :or { is-critical false sort-keys []}}]
  (if (not (empty? sort-keys))
    (let [keylist (map (fn [[k v]] (SortKey. (name k) (sortOrder v)))
                       (apply array-map sort-keys))]
      (ServerSideSortRequestControl. is-critical (into-array SortKey keylist)))
    (throw (Exception. "Error: The search option 'server-sort' requires
                        non-empty sort-keys"))))

(defn- search-criteria
  "Given a map of search criteria and possibly other keys, return the same map
   with search criteria keys rewritten ready for passing to search functions."
  [base {:keys [scope filter attributes size-limit time-limit types-only
                proxied-auth controls respf server-sort byte-valued]
         :as original
         :or {size-limit 0 time-limit 0 types-only false byte-valued []
              filter "(objectclass=*)" controls [] respf nil
              proxied-auth nil server-sort nil}}]
  (let [server-sort-control (if (not-nil? server-sort)
                              [(createServerSideSort server-sort)]
                              [])
        proxied-auth-control (if (not-nil? proxied-auth)
                           [(ProxiedAuthorizationV2RequestControl. proxied-auth)]
                           [])]
    (merge original {:base       base
                     :scope      (get-scope scope)
                     :filter     filter
                     :attributes (get-attributes attributes)
                     :size-limit size-limit :time-limit time-limit
                     :types-only types-only
                     :controls   (-> controls
                                     (into server-sort-control)
                                     (into proxied-auth-control))})))

;;=========== API ==============================================================

(defn connect
  "Connects to an ldap server and returns a thread-safe LDAPConnectionPool.
   Options is a map  with the following entries:
   :host                Either a string in the form \"address:port\"
                        OR a map containing the keys,
                           :address   defaults to localhost
                           :port      defaults to 389 (or 636 for ldaps),
                        OR a collection containing multiple hosts used for load
                        balancing and failover. This entry is optional.
   :bind-dn             The DN to bind as, optional
   :password            The password to bind with, optional
   :num-connections     Establish a fixed size connection pool. Defaults to 1.
   :initial-connections Establish a connection pool initially of this size with
                        capability to grow to :max-connections. Defaults to 1.
   :max-connections     Define maximum size of connection pool. It must be 
                        greater than or equal to the initial number of 
                        connections, defaults to value of :initial-connections.
   :ssl?                Boolean, connect over SSL (ldaps), defaults to false
   :startTLS?           Boolean, use startTLS over non-SSL port, defaults to false
   :trust-store         Only trust SSL certificates that are in this
                        JKS format file, optional, defaults to trusting all
                        certificates
   :connect-timeout     The timeout for making connections (milliseconds),
                        defaults to 1 minute
   :timeout             The timeout when waiting for a response from the server
                        (milliseconds), defaults to 5 minutes
   "
  [options]
  (let [host (options :host)]
    (if (and (coll? host)
             (not (map? host)))
      (connect-to-hosts options)
      (connect-to-host options))))

(defn get-connection
  "Returns a connection from the LDAPConnectionPool object. This approach is
   only needed when a sequence of operations must be performed on a single
   connection. For example: get-connection, bind?, modify (as the bound user).
   The connection should be released back to the pool after use."
  [pool]
  (.getConnection pool))

(defn release-connection
  "Returns the original connection pool with the provided connection released
   and reauthenticated."
  [pool connection]
  (.releaseAndReAuthenticateConnection pool connection))

(defn bind?
  "Performs a bind operation using the provided connection, bindDN and
password. Returns true if successful.

When an LDAP connection object is used as the connection argument the
bind? function will attempt to change the identity of that connection
to that of the provided DN. Subsequent operations on that connection
will be done using the bound identity.

If an LDAP connection pool object is passed as the connection argument
the bind attempt will have no side-effects, leaving the state of the
underlying connections unchanged."
  [connection bind-dn password]
  (try
    (let [r (if (instance? LDAPConnectionPool connection)
              (.bindAndRevertAuthentication connection bind-dn password nil)
              (.bind connection bind-dn password))]
      (= ResultCode/SUCCESS (.getResultCode r)))
    (catch Exception _ false)))

(defn close
  "closes the supplied connection or pool object"
  [conn]
  (.close conn))

(defn who-am-i
  "Return the authorization identity associated with this connection."
  [connection]
  (let [^WhoAmIExtendedResult res (.processExtendedOperation
                                    connection (WhoAmIExtendedRequest.))]
    (if (= ResultCode/SUCCESS (.getResultCode res))
      (let [authz-id (.getAuthorizationID res)]
        (cond
          (or (= authz-id "") (= authz-id "dn:")) ""
          (.startsWith authz-id "dn:") (subs authz-id 3)
          (.startsWith authz-id "u:") (subs authz-id 2)
          :else authz-id)))))

(defn get
  "If successful, returns a map containing the entry for the given DN.
   Returns nil if the entry doesn't exist or cannot be read. Takes an
   optional collection that specifies which attributes will be returned
   from the server."
  ([connection dn]
   (get connection dn nil))
  ([connection dn attributes]
   (get connection dn attributes []))
  ([connection dn attributes byte-valued]
   (if-let [result (if attributes
                     (.getEntry connection dn
                                (into-array java.lang.String
                                            (map name attributes)))
                     (.getEntry connection dn))]
      ((entry-as-map byte-valued) result))))

(defn add
  "Adds an entry to the connected ldap server. The entry is assumed to be
   a map. The options map supports control :proxied-auth."
  ([connection dn entry]
    (add connection dn entry nil))
  ([connection dn entry options]
   (let [entry-obj (Entry. dn)]
     (set-entry-map! entry-obj entry)
     (when options
       (add-request-controls entry-obj options))
     (ldap-result
       (.add connection entry-obj)))))

(defn compare?
  "Determine whether the specified entry contains a given attribute value.
   The options map supports control :proxied-auth."
  ([connection dn attribute assertion-value]
   (compare? connection dn attribute assertion-value nil))
  ([connection dn attribute assertion-value options]
   (let [request (CompareRequest. dn (name attribute) assertion-value)]
     (when (and options (:proxied-auth options))
       (.addControl request (ProxiedAuthorizationV2RequestControl.
                              (:proxied-auth options))))
     (.compareMatched (.compare connection request)))))

(defn modify
  "Modifies an entry in the connected ldap server. The modifications are
   a map in the form:
     {:add
        {:attribute-a some-value
         :attribute-b [value1 value2]}
      :delete
        {:attribute-c :all
         :attribute-d some-value
         :attribute-e [value1 value2]}
      :replace
        {:attibute-d value
         :attribute-e [value1 value2]}
      :increment
        {:attribute-f value}
      :pre-read
        #{:attribute-a :attribute-b}
      :post-read
        #{:attribute-c :attribute-d}}

Where :add adds an attribute value, :delete deletes an attribute value and
:replace replaces the set of values for the attribute with the ones specified.
The entries :pre-read and :post-read specify attributes that have be read and
returned either before or after the modifications have taken place."
  ([connection dn modifications]
    (modify connection dn modifications nil))
  ([connection dn modifications options]
   (let [modify-obj (get-modify-request dn modifications)]
     (when options
       (add-request-controls modify-obj options))
     (ldap-result
       (.modify connection modify-obj)))))

(defn modify-password
  "Creates a new password modify extended request that will attempt to change
   the password of the currently-authenticated user, or another user if their
   DN is provided and the caller has the required authorisation."
  ([connection new]
   (let [request (PasswordModifyExtendedRequest. new)]
     (.processExtendedOperation connection request)))

  ([connection old new]
   (let [request (PasswordModifyExtendedRequest. old new)]
     (.processExtendedOperation connection request)))

  ([connection old new dn]
   (let [request (PasswordModifyExtendedRequest. dn old new)]
     (.processExtendedOperation connection request))))

(defn modify-rdn
  "Modifies the RDN (Relative Distinguished Name) of an entry in the connected
  ldap server.

  The new-rdn has the form cn=foo or ou=foo. Using just foo is not sufficient.
  The delete-old-rdn boolean option indicates whether to delete the current
  RDN value from the target entry. The options map supports pre/post-read
  and proxied-auth controls."
  ([connection dn new-rdn delete-old-rdn]
    (modify-rdn connection dn new-rdn delete-old-rdn nil))
  ([connection dn new-rdn delete-old-rdn options]
   (let [request (ModifyDNRequest. dn new-rdn delete-old-rdn)]
     (when options
       (add-request-controls request options))
     (ldap-result
       (.modifyDN connection request)))))

(defn delete
  "Deletes the given entry in the connected ldap server. Optionally takes
   a map that can contain:
      :pre-read        A set of attributes that should be read before deletion
                       (only applied to base entry if used with :delete-subtree)
      :proxied-auth    The dn:<dn> or u:<uid> to be used as the authorization
                       identity when processing the request.
      :delete-subtree  If truthy, deletes the entire subtree of DN (server must
                       support Subtree Delete Control, 1.2.840.113556.1.4.805)"
  ([connection dn]
   (delete connection dn nil))
  ([connection dn options]
   (let [delete-obj (DeleteRequest. dn)]
     (when options
       (add-request-controls delete-obj options))
     (ldap-result
      (.delete connection delete-obj)))))

;; For the following search functions.
;; Options is a map with the following optional entries:
;;    :scope       The search scope, can be :base :one :sub or :subordinate,
;;                 defaults to :sub
;;    :filter      A string representing the search filter,
;;                 defaults to "(objectclass=*)"
;;    :attributes  A collection of the attributes to return,
;;                 defaults to all user attributes
;;    :byte-valued A collection of attributes to return as byte arrays as
;;                 opposed to Strings.
;;    :size-limit  The maximum number of entries that the server should return
;;    :time-limit  The maximum length of time in seconds that the server should
;;                 spend processing this request
;;    :types-only  Return only attribute names instead of names and values
;;    :server-sort Instruct the server to sort the results. The value of this
;;                 key is a map like the following:
;;                   :is-critical ( true | false )
;;                   :sort-keys [ :cn :ascending
;;                                :employeNumber :descending ... ]
;;                 At least one sort key must be provided.
;;    :proxied-auth    The dn:<dn> or u:<uid> to be used as the authorization
;;                     identity when processing the request.
;;    :controls    Adds the provided controls for this request.
;;    :respf       Applies this function to all response controls present.

(defn search-all
  "Uses SimplePagedResultsControl to search on the connected ldap server,
  and returns the results as a lazy sequence of maps."
  ([connection base]
   (search-all connection base nil))
  ([connection base options]
   (search-all-results connection (search-criteria base options))))

(defn search
  "Runs a search on the connected ldap server, reads all the results into
   memory and returns the results as a sequence of maps."
  ([connection base]
   (search connection base nil))
  ([connection base options]
   (search-results connection (search-criteria base options))))

(defn search!
  "Runs a search on the connected ldap server and executes the given
   function (for side effects) on each result. Does not read all the
   results into memory."
  ([connection base f]
   (search! connection base nil f))
  ([connection base options f]
   (search-results! connection (search-criteria base options) f)))
