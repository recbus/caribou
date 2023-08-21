# Caribou - A hardy migrator

Being confident about the shape of your database can dramatically simplify your application code.  In conjunction with state management
tools like [Component](https://github.com/stuartsierra/component) or [Integrant](https://github.com/weavejester/integrant), Caribou
allows you to express transformations that must be performed exactly once prior to your application startup.  Caribou enforces these
intransients while retaining maximum deployment flexibility, supporting parallel development of independent features and preserving
a faithful record of historical migrations.

The driving principles behind caribou are:

1. that migrations are arbitrary transactions;
2. that migrations are best expressed as a dependency graph;
3. and that the only reliable record of prior migrations is the database itself.

## Migrations are arbitrary transactions
Migrations must be atomic, consistent, isolated and durable (ACID) and so caribou encourages you to represent a migration
as a single transaction.  Otherwise caribou is unopinionated, allowing the transaction data to be schema changes, seed data
additions, domain data corrections, etc.

Migration transaction data can be either literal data (`tx-data`) or the return value of a (presumably pure) user-supplied
`tx-data-fn` function.  Transaction data can include attributes of the transaction itself (a "reified" transaction).

## Migrations are best expressed as a dependency graph
"Later" migrations can depend on prior migrations.  For example, in order to transact seed data (migration "B") the underlying
schema (migration "A") must have been previously transacted.  But migrations are not simply a linear chain of dependencies: schema
migration "C" might be totally independent of schema migration "D".  It is valuable to preserve the flexibility inherent in
independent migrations -especially when working on teams building independent features.

In caribou, topological sorting of the migration dependency *graph* informs the order in which migrations are applied when multiple
migrations are pending.  And because a migration dependency graph may have multiple valid topological sorts, caribou allows the 
history of applied migrations to vary as long as the order is a valid topological sort.

Consider the following migrations, where each key is the name of a migration and its dependencies are the associated value (a set):

``` clojure
{:A #{}
 :B #{:A}
 :C #{:A}
 :D #{:B :C}}
```
In this example, there are two equally valid ordering of migrations (`[:A :C :B :D]` and `[:A :B :C :D]`).  By tolerating either 
order, caribou allows the features associated with migrations `:B` and `:C` to be developed and deployed in any order.  This 
flexibility can even allow parallel development against a shared database.

## The only reliable record of prior migrations is the database itself
It is tempting to assume that the source (idiomatically, an EDN data file) of migration transaction data is a reliable record
of the applied migrations.  However, reality sometimes intrudes on this ideal with, human error being the primary culprit: 

 * a bad merge changes the migration source files;
 * a migration under development is accidentally applied (hopefully never to a production database!!);
 * an un-repeatable source of migration data is accidentally transacted.
 
As bad as these mistakes are, they are compounded exponentially when they go unnoticed.  Caribou always verifies the 
cryptographic signature of migration source data with the signature recorded in the database at the time the migration
was performed.  If they differ, caribou refuses to proceed until either the correct source data is restored or a new 
epoch is declared -at which point the database state is the sole source of truth for prior migrations and new migrations
start a new dependency graph.

### Expressing Migrations
The caribou migration graph is represented as a map of `<migration name>` to `<migration datas>`.  Idiomatically this graph
is pure data stored in an [edn](https://github.com/edn-format/edn) file that is read at system startup with, e.g. 
[clojure.edn/read](https://clojuredocs.org/clojure.edn/read) or [aero](https://github.com/juxt/aero).

#### Migration Reference
| term  | definition  |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| _migration_      | {_migration-name_ _migration-data_} |
| _migration-name_ | a Clojure keyword. |
| _migration-data_ | {`:tx-data` _tx-data_, `:tx-data-fn` _tx-data-fn_, `:dependencies` _dependencies_, `:step-fn` _step-fn_, `:context` _context_} |
| _tx-data_        | Datomic [transaction data](https://docs.datomic.com/cloud/transactions/transaction-data-reference.html) with the same constraints as those of [datomic.client.api/transact](https://docs.datomic.com/client-api/datomic.client.api.html#var-transact) |
| _tx-data-fn_     | A qualified symbol naming a pure function of _context_ that returns _tx-data_ |
| _dependencies_   | A Clojure set of the migration's dependencies referenced by _migration_name_ |
| _step-fn_        | A qualified symbol naming an opaque step function satisfying the requirements of [clojure.core/iteration](https://www.juxt.pro/blog/new-clojure-iteration/) |
|_context_         | arbitrary Clojure data passed to _tx-data-fn_ or _step-fn_ |

#### Migration Example
Here's a simple example of a single migration:

``` clojure
{...
 :st.schema/state+county-numeric     {:tx-data
                                      [{:db/id "datomic.tx", :st.db/provenance "Schema Migration"}
                                       {:db/ident       :st.county/state+county-numeric,
                                        :db/doc         "The tuple of state (a reference) and county-numeric that uniquely identifies this county",
                                        :db/valueType   :db.type/tuple,
                                        :db/tupleAttrs  [:st.county/state :ansi.fips/county-numeric],
                                        :db/cardinality :db.cardinality/one,
                                        :db/unique      :db.unique/identity}],
                                      :dependencies [:st.schema/tx-metadata :st.schema/ansi-counties]}
...}
```

In the above example, the migration named `:st.schema/state+county-numeric` adds a schema attribute and transaction metadata.  It
declares two dependencies whose role can be inferred from the tx data (adding the `:st.db/provenance` to the transaction metadata 
and adding a tuple schema attribute).

##### Notes on various means of defining the migration transaction(s).
A migration can define its transaction data in three ways.

1. `:tx-data`
Transaction data defined at the `:tx-data` key is semantically identical to the `:tx-data` given to `d/transact`.  For schema migrations
and other low-volume or hand-crafted data, this is an effective way to define a migration.  With data/configuration reading tools (e.g. 
[aero](https://github.com/juxt/aero#include), integrant) or some pre-processing, it's possible to include subordinate EDN files directly
in your migration data so as to keep the clutter in your top-level migration file to a minimum.

2. `:tx-data-fn`
When the migration transaction data is best sourced from an external file or some computation, the `:tx-data-fn` allows you to generate 
transaction data.  Caribou will require/resolve the `:tx-data-fn` symbol and invoke the named function with the `:context` argument (or nil
if not defined).  The function must return valid `:tx-data`.

If both `:tx-data` and `:tx-data-fn` are defined, caribou will concatenate the two into one transaction.  A typical use case is to define
hand-crafted transaction metadata directly in the migration source file and source voluminous seed data through a simple function that reads
(constant) external data, e.g. a CSV file.

3. `:step-fn`
Unlike the `:tx-data` and `:tx-data-fn` features above, `:step-fn` allows you to perform a sequence of arbitrary, potentially side-effecting
operations.  There are no transactional semantics around the collection of operations performed, but caribou does independently transact the
`:tx-data`-shaped return value of each invocation of `:step-fn` along with metadata indicating that the step was completed.  Use `:step-fn` 
when you need to perform external side-effects, possibly loosely coordinated with database transactions.  When a migration includes a `:step-fn`
in addition to `:tx-data` or `:tx-data-fn`, the iterations of the step function are performed first and the "proper" migration data is transacted
last.

The provided step function is executed in the context of the Clojure (> 1.11.0) `iteration` function, subject to the following:

a. The initial token is the provided _context_ map augmented with the current database value at the `:db` key.
b. The continutation token/context map returned by the step funciton may have a `:tx-data` key whose contents will be transacted along with 
tx metadata indicating the step (identified by its sequence index) was completed.
d. If the step function returns nil or false, iteration is halted.

#### Notes on Migration Integrity
Once a migration has been applied, do not change its source data.  The correlation of each applied migration to its source data is a
fundamental feature of caribou.

To ensure that the critical source data is not corrupt (relative to the actual transacted data) caribou computes the signature (a 
cryptographic hash) of each migration and transacts it along wth the migration's transaction data payload.  This signature authenticates
the transaction data of the migration (whether supplied directly by `:tx-data` or returned by `:tx-data-fn`) as well as its (recursive)
dependencies.  Note that even though Datomic has two ways of expressing transaction data (entity maps and datom vectors) the signature
is computed from the Clojure data structures used to express the transaction data, not the resulting datoms.  You cannot switch from entity
maps to datom vectors without changing the signature of a migration.

The hash of a `:step-fn` migration is a special case: caribou considers the step function's presence in a migration and ignores its side 
effects and even its symbol name (thus allowing for namespace refactors).  Beware of this limitation when you really care about "identical" 
database shapes across time and database instances.  Step functions are intended for cases where off-book (outside Datomic) side effects must
be coordinated with database transactions.

If the hash computed locally from the source data does not match the recorded hash in the database _for any applied migration in the same
epoch_, caribou will throw an exception and refuse to apply any further migrations.  If this happens you have two options:

a. Restore or reconstitute the migration source data that generated the transacted migrations.
b. Declare a new epoch.

Declaring a new epoch acknowledges that the state of the database has irrevocably diverged from any available source data.  This is a bitter
pill to swallow, particularly for testing, as the migration state of the database can't be reproduced.  Avoid it vigorously.  But if you must, 
it is possible to start a new epoch with an empty migration data source (map) by incrementing the dynamic variable `*io.recbus.caribou/epoch*` 
to a value greater than any previously transacted migration.  Thereafter, all existing migrations from the previous epoch (zero, by default) 
are ignored and new migrations can be applied.
