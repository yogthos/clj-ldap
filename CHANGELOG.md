# Change Log
All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](http://semver.org/).

## [0.0.16]
This release adds the following:
- A lazy-seq returned by `search-all-results` using SimplePagedResults control. Thanks to @jindrichmynarz.

## [0.0.15]
This release adds the following:
- A fix of StartTLS support.

## [0.0.14]
This release adds the following:
- The :initial-connections and :max-connections options to the connect function, without deprecating the :num-connections option.

## [0.0.13]
This release adds the following:
- The :delete-subtree option to the delete function, which requires the support of the Subtree Delete Request Control on the server

## [0.0.12]
This release adds the following:
- The :proxied-auth option to most functions
- The compare? function which invokes LDAP compare operation
- get-connection and release-connection functions which promote proper use of the connection pool when a sequence of operations must be performed on a single connection

## [0.0.11]
This release addresses a bug introduced in 0.0.10 and bumps the ldap-sdk version to 3.1.1.
- Fix bug preventing use of :pre-read and :post-read options to modify and delete
- Introduce test coverage of :pre-read and :post-read

## [0.0.10]
### Binary (byte-array) attribute values
The behavior of the api towards binary valued attributes has been fixed. Thanks to Ray Miller for providing the 
changes to `create-modification`. In addition, the api now returns binary data as it was orginally submitted as opposed
to base64 encoded. Base64 encoding is only needed if the client requests a text representation of the LDAP entry, such as LDIF. A byte-valued collection of attribute names (as keywords) can be provided to all search functions to instruct the api to return
byte arrays as opposed to strings.

### Bind? Fix
A bug was discovered and fixed in `bind?` which would leave connections in the pool with an authorization ID
associated with previous BIND. Thanks to Adam Harper and Sam Umbach for debugging. The `bind?` function
now acts as follows:
- If passed a connection pool (which is the default behavior, `connect` returns a connection pool) then the BIND is
performed with a `pool.bindAndRevertAuthentication` which returns the autherization ID back to what it was before the BIND.
- If passed an individual LDAPConnection (which can be retrieved via `(.getConnection pool)`, then
the BIND is performed as usual and the caller is responsible for using `(.releaseAndReAuthenticateConnection pool conn)`
to release the connection back to the pool.

### Added
- size-limit, time-limit and types-only options to search.
- StartTLS support with the startTLS? boolean option to connect.
- byte-valued option to search functions which implies byte array values are to be returned for these attributes.
- controls option to search. This allows passing in arbitrary controls which have been properly instantiated via java interOp.
- respf option to search. This function, if defined, will be invoked on the list of response controls, if present.
- server-sort option to search which attaches a ServerSideSortRequestControl to the search operation.
- Who Am I? extended request.
- Unit tests for the above.

### Replaced
- test.server has been rewritten to incorporate the InMemoryDirectoryServer provided in the UnboundID LDAP SDK.
