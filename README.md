# Caribou - A hardy migrator

Being confident about the shape of your database can dramatically simplify your application code.  With state management tools
like component or integrant, Caribou allows you to express transactions that must be performed prior to your application startup 
while retaining maximum flexibility to work in parallel with other developers.

The driving principles behind caribou are:

0. that migrations are arbitrary transactions;
1. that migrations are best expressed as a dependency graph;
3. and that the only reliable record of prior migrations is the database itself.

## Migrations are arbitrary transactions
Migrations must be atomic, consistent, isolated and durable (ACID) and so caribou encourages you to represent a migration
as a single transaction.  Otherwise caribou is unopinionated, allowing the transaction data to be schema changes, seed data
additions, etc.

Migration transaction data can be either literal data (`tx-data`) or the return value of a (presumably pure) supplied `tx-data-fn`
function.  Transaction data can include attributes of the transaction itself (a "reified" transaction).

## Migrations are best expressed as a dependency graph
"Later" migrations can depend on prior migrations.  In order to transact seed data (migration "B"), for example, the underlying
schema (migration "A") must have been previously transacted.  But migrations are not simply a linear chain of dependencies: schema
migration "C" might be totally independent of schema migration "D".  It is valuable to preserve the flexibility inherent in
independent migrations -especially when working on teams building independent features.

In caribou, topological sorting of the migration dependency graph informs the order in which migrations are applied when multiple
migrations are pending.  And because a migration dependency graph may have multiple valid topological sorts when independent
migrations are present, caribou allows the history of applied migrations to vary as long as the order is a valid topological 
sorting.

Consider the following migrations, where each key is the name of a migration and its dependencies are the associated value:

``` clojure
{:A #{}
 :B #{:A}
 :C #{:A}
 :D #{:B :C}}
```
In this example, there are two possible orders: `[:A :C :B :D]` and `[:A :B :C :D]`.  By allowing either order, caribou allows
the features associated with migrations `:B` and `:C` to be performed in any order.  This flexibility simplifies production 
deployments and can even allow parallel development against a shared database.

## The only reliable record of prior migrations is the database itself
It's tempting to assume that the external source of migration transaction data is a reliable record of the applied migrations.
However, reality sometimes intrudes on this ideal.  Human error is the primary culprit: 

 * a bad merge changes the migration source files;
 * a migration under development is accidentally applied (hopefully never to a production database!!);
 * an un-repeatable source of migration data is accidentally transacted.
 
As bad as these mistakes are, they are compounded exponentially when they go unnoticed.  Caribou always verifies the cryptographic
signature of migration source data with the signature recorded in the database at the time the migration was performed.  If they
differ, caribou refuses to proceed until either the source data is restored or a new epoch is declared -at which point the database
state is the sole source of truth for the current state -with no external basis.
