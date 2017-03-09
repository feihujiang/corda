Soft Locking
============

Soft Locking is implemented in the vault to prevent multiple transactions trying to use the same output(s) simultaneously.
Violation of a soft lock would result in a double spend being created and rejected by the notary.

Soft locks are automatically applied to coin selection (eg. cash spending) to ensure that no two transactions attempt to 
spend the same fungible states. The outcome of such an eventuality will result in an ``InsufficientBalanceException`` for one
of the requesters if there are insufficient number of fungible states available to satisfy both requests.

.. note:: The Cash Contract schema table is now automatically generated upon node startup as Coin Selection now uses
          this table to ensure correct locking and selection of states to satisfy minimum requested spending amounts.

In addition, the ``VaultService`` exposes a number of functions a developer may use to explicitly reserve, release and
query soft locks associated with states as required by their CorDapp application logic:

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/node/services/Services.kt
    :language: kotlin
    :start-after: DOCSTART SoftLockAPI
    :end-before: DOCEND SoftLockAPI

The general purpose API function for retrieving states has also been enhanced to include a flag indicating whether soft locked
states should be included:

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/node/services/Services.kt
    :language: kotlin
    :start-after: DOCSTART VaultStatesQuery
    :end-before: DOCEND VaultStatesQuery

Explicit Usage
--------------

Soft locks are associated with transactions, and typically within the lifecycle of a flow. Specifically, every time a
flow is started a soft lock identifier is associated with that flow for its duration (and released upon it's natural
termination or in the event of an exception). The ``VaultSoftLockManager`` is responsible within the Node for
automatically managing this soft lock registration and release process for flows. The ``TransactionBuilder`` class has a
new ``lockId`` field for the purpose of tracking lockable states. By default, it is automatically set to a random
``UUID`` (outside of a flow) or to a flow's unique ID (within a flow).

Upon building a new transaction to perform some action for a set of states on a contract, a developer must explicitly
register any states they may wish to hold for the duration of that transaction. These states will be effectively 'soft
locked' (not usable by any other transaction) until the developer explicitly releases these or the flow terminates or errors 
(at which point they are automatically released).

Example
-------

An example of *soft locking* in action can be seen in the ``IssuerFlow``, whereby an amount of Cash is issued to one
party (eg. a central bank) and then transferred to a destination recipient (eg. a client corporation). To ensure this
operation is atomic  (eg. issue and transfer use the same set of unconsumed, spendable states), we explicitly soft lock
the states upon issuance and then release them after transfer.

.. literalinclude:: ../../finance/src/main/kotlin/net/corda/flows/IssuerFlow.kt
    :language: kotlin
    :start-after: DOCSTART SoftLockExample
    :end-before: DOCEND SoftLockExample

Other use cases for *soft locking* may involve competing flows attempting to match trades or any other concurrent
activities that may involve operating on an identical set of unconsumed states.
