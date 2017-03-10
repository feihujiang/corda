package net.corda.node.services.vault
import net.corda.core.contracts.StateRef
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.services.VaultService
import net.corda.core.utilities.loggerFor
import net.corda.node.services.statemachine.StateMachineManager
import net.corda.node.utilities.AddOrRemove
import java.util.*

class VaultSoftLockManager(val vault: VaultService, smm: StateMachineManager) {

    private companion object {
        val log = loggerFor<VaultSoftLockManager>()
    }

    private val trackingFlowIds: MutableSet<UUID> = HashSet()

    init {
        smm.changes.subscribe { change ->
            log.trace( "${change.addOrRemove} Flow name ${change.logic.javaClass} with id ${change.id}")
            if (change.addOrRemove == AddOrRemove.REMOVE && trackingFlowIds.contains(change.id.uuid)) {
                unregisterSoftLock(change.id, change.logic)
                trackingFlowIds.remove(change.id.uuid)
            }
        }
        vault.rawUpdates.subscribe { update ->
            update.flowId?.let {
                if (update.produced.isNotEmpty()) {
                    registerSoftLock(update.flowId, update.produced.map { it.ref })
                    trackingFlowIds.add(update.flowId)
                }
            }
        }
    }

    private fun registerSoftLock(flowId: UUID, stateRefs: List<StateRef>) {
        log.trace("Reserving soft locks for flow id $flowId and states $stateRefs")
        vault.softLockReserve(flowId, stateRefs.toSet())
    }

    private fun  unregisterSoftLock(id: StateMachineRunId, logic: FlowLogic<*>) {
        val flowClassName = logic.javaClass.simpleName
        log.trace("Releasing soft locks for flow $flowClassName with flow id ${id.uuid}")
        vault.softLockRelease(id.uuid)

    }
}