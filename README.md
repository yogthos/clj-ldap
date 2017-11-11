
# Introduction

clj-ldap is a thin layer on the [unboundid sdk](http://www.unboundid.com/products/ldap-sdk/) and allows clojure programs to talk to ldap servers. This library is available on [clojars.org](http://clojars.org/search?q=clj-ldap)
```clojure
     :dependencies [[org.clojars.pntblnk/clj-ldap "0.0.15"]]
```
# Example 

```clojure
    (ns example
      (:require [clj-ldap.client :as ldap]))
      
    (def ldap-server (ldap/connect {:host "ldap.example.com"}))
    
    (ldap/get ldap-server "cn=dude,ou=people,dc=example,dc=com")
    
    ;; Returns a map such as
    {:gidNumber "2000"
     :loginShell "/bin/bash"
     :objectClass #{"inetOrgPerson" "posixAccount" "shadowAccount"}
     :mail "dude@example.com"
     :sn "Dudeness"
     :cn "dude"
     :uid "dude"
     :homeDirectory "/home/dude"}
```

# API

## connect [options]

Connects to an ldap server and returns a, thread safe, [LDAPConnectionPool](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPConnectionPool.html).
Options is a map with the following entries:

    :host                Either a string in the form "address:port"
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

Throws an [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if an error occurs establishing the connection pool or authenticating to any of the servers.
Some examples:
```clojure
    (ldap/connect {:host "ldap.example.com"
                   :num-connections 4
                   :bind-dn "cn=admin,dc=example,dc=com"
                   :password "password"})
    
    (ldap/connect {:host [{:address "ldap1.example.com" :port 1389}
                          {:address "ldap3.example.com"}
                          "ldap2.example.com:1389"]
                   :startTLS? true
                   :initial-connections 9
                   :max-connections 18
                   :bind-dn "cn=directory manager"
                   :password "password"})
                        
    (ldap/connect {:host {:port 1389}
                   :bind-dn "cn=admin,dc=example,dc=com"
                   :password "password"})
```
The pool can then be used as a parameter to all the functions in the library where
a connection is expected. Using a pool in this manner aleviates the caller from having to get and
release connections. It will still be necessary to get and release a connection if a single
connection is needed to process a sequence of operations. See the following bind? example.

## bind? [connection bind-dn password] [connection-pool bind-dn password]

Usage:
```clojure
    (ldap/bind? pool "cn=dude,ou=people,dc=example,dc=com" "somepass")

    (let [conn (ldap/get-connection pool)
          user-dn "uid=user.1,ou=people,dc=example,dc=com"
          user-password "password"]
      (try
        (when (ldap/bind? conn user-dn user-password)
          (ldap/modify conn user-dn {:replace {:description "On sabatical"}}))
        (finally (ldap/release-connection pool conn))))
```
Performs a bind operation using the provided connection, bindDN and
password. Returns true if successful.

If an LDAPConnectionPool object is passed as the connection argument
the bind attempt will have no side-effects, leaving the state of the
underlying connections unchanged.

When an LDAP connection object is used as the connection argument the
bind? function will attempt to change the identity of that connection
to that of the provided DN. Subsequent operations on that connection
will be done using the bound identity.

## get [connection dn] [connection dn attributes]
  
If successful, returns a map containing the entry for the given DN.
Returns nil if the entry doesn't exist. 
```clojure
    (ldap/get conn "cn=dude,ou=people,dc=example,dc=com")
```
Takes an optional collection that specifies which attributes will be returned from the server.
```clojure
    (ldap/get conn "cn=dude,ou=people,dc=example,dc=com" [:cn :sn])
```
Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) on error.

## add [connection dn entry]

Adds an entry to the connected ldap server. The entry is map of keywords to values which can be strings, sets or vectors.

```clojure
    (ldap/add conn "cn=dude,ou=people,dc=example,dc=com"
                   {:objectClass #{"top" "person"}
                    :cn "dude"
                    :sn "a"
                    :description "His dudeness"
                    :telephoneNumber ["1919191910" "4323324566"]})
```
Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if there is an error with the request or the add failed.

## compare? [connection dn attribute assertion-value]

Determines if the specified entry contains the given attribute and value.

```clojure
    (ldap/compare? conn "cn=dude,ou=people,dc=example,dc=com"
                   :description "His dudeness")
```
Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if there is an error with the request or the LDAP compare failed.

## modify [connection dn modifications]                    

Modifies an entry in the connected ldap server. The modifications are
a map in the form:
```clojure
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
```
Where :add adds an attribute value, :delete deletes an attribute value and :replace replaces the set of values for the attribute with the ones specified. The entries :pre-read and :post-read specify attributes that have be read and returned either before or after the modifications have taken place. 

All the keys in the map are optional e.g:
```clojure
     (ldap/modify conn "cn=dude,ou=people,dc=example,dc=com"
                  {:add {:telephoneNumber "232546265"}})
```
The values in the map can also be set to :all when doing a delete e.g:
```clojure
     (ldap/modify conn "cn=dude,ou=people,dc=example,dc=com"
                  {:delete {:telephoneNumber :all}}
                  {:proxied-auth "dn:cn=app,dc=example,dc=com"})
```
The values of the attributes given in :pre-read and :post-read are available in the returned map and are part of an atomic ldap operation e.g
```clojure
     (ldap/modify conn "uid=maxuid,ou=people,dc=example,dc=com"
                  {:increment {:uidNumber 1}
                   :post-read #{:uidNumber}})
```
returns
```clojure
       {:code 0
        :name "success"
        :post-read {:uidNumber "2002"}}
```
The above technique can be used to maintain counters for unique ids as described by [rfc4525](http://tools.ietf.org/html/rfc4525).

Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) on error.

## search [connection base]  [connection base options]

Runs a search on the connected ldap server, reads all the results into
memory and returns the results as a sequence of maps. An introduction
to ldap searching can be found in this [article](http://www.enterprisenetworkingplanet.com/netsysm/article.php/3317551/Unmasking-the-LDAP-Search-Filter.htm).

Options is a map with the following optional entries:

    :scope       The search scope, can be :base :one :sub or :subordinate,
                 defaults to :sub
    :filter      A string representing the search filter,
                 defaults to "(objectclass=*)"
    :attributes  A collection of the attributes to return,
                 defaults to all user attributes
    :byte-valued A collection of attributes to return as byte arrays as
                 opposed to Strings.
    :size-limit  The maximum number of entries that the server should return
    :time-limit  The maximum length of time in seconds that the server should
                 spend processing this request
    :types-only  Return only attribute names instead of names and values
    :server-sort Instruct the server to sort the results. The value of this
                 key is a map like the following:
                 { :is-critical ( true | false )
                   :sort-keys [ :cn :ascending
                                :employeNumber :descending ... ] }
                 At least one sort key must be provided.
    :proxied-auth    The dn:<dn> or u:<uid> to be used as the authorization
                     identity when processing the request. Don't forget the dn:/u: prefix.
    :controls    Adds the provided controls for this request.
    :respf       Applies this function to the list of response controls present.

e.g
```clojure
    (ldap/search conn "ou=people,dc=example,dc=com")
    
    (ldap/search conn "ou=people,dc=example,dc=com" {:attributes [:cn] :sizelimit 100
                                                     :proxied-auth "dn:cn=app,dc=example,dc=com"})

    (ldap/search conn "dc=example,dc=com" {:filter "(uid=abc123)" :attributes [:cn :uid :userCertificate]
                                           :byte-valued [:userCertificate]})
```
Throws a [LDAPSearchException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPSearchException.html) on error. This function will not throw the exception in the event
of a size limit exceeded result, instead the entries are returned.

## search! [connection base f]   [connection base options f]

Runs a search on the connected ldap server and executes the given
function (for side effects) on each result. Does not read all the
results into memory. The options argument is a map similar to that of the search
function defined above. e.g
```clojure
     (ldap/search! conn "ou=people,dc=example,dc=com" println)
     
     (ldap/search! conn "ou=people,dc=example,dc=com"
                        {:filter "sn=dud*"}
                        (fn [x]
                           (println "Hello " (:cn x))))
```
Throws a [LDAPSearchException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPSearchException.html) if an error occurs during search. Throws an [EntrySourceException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/EntrySourceException.html) if there is an error obtaining search results.

## delete [connection dn] [connection dn options]

Deletes the given entry in the connected ldap server.

Options is a map with the following optional entries:

    :delete-subtree  Use the Subtree Delete Control to delete entire subtree rooted at dn.
    :pre-read        A set of attributes that should be read before deletion (will only apply to base entry if used with :delete-subtree).
    :proxied-auth    The dn:<dn> or u:<uid> to be used as the authorization
                     identity when processing the request. Don't forget the dn:/u: prefix.
```clojure
     (ldap/delete conn "cn=dude,ou=people,dc=example,dc=com")

     (ldap/delete conn "cn=dude,ou=people,dc=example,dc=com" 
                       {:pre-read #{:telephoneNumber}})
```
Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if the object does not exist or an error occurs.
