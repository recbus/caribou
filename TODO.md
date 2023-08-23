* Abstract away the dependency on `com.datomic/client-cloud` and allow for use with other clients.
* Clean up the messy code allowing hashing of the dependency tree with the basis stuffed into metadata. 
After `prepare`, consider transitioning to a three-tuple of `[depencies tx-data step-fn]` which, when
considered in the context of a map-entry, becomes `[name [dependencies tx-data step-fn]]`.  Store the
hash on the metadata of this structure.
* Optionally create the tx-data on demand when a migration is missing in the database.  This implicitly
relaxes the integrity checks of the applied migration tree versus the source data so it should not be the 
default.  Semantically, this is similar to declaring a new epoch though, so don't invest too much here.
* To shrink the memory footprint of the prepared migration source, consider a lazy tree where a node's
tx-data is only fully-realized (`tx-data-fn` evaluated) when needed for a one-time computation of its 
integrity hash or, if not yet applied, for eventual transacting.
