package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.oracle.Oracle
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

/** Called by the oracle to provide a stock's spot price to a client. */
@InitiatedBy(OracleQuery::class)
@CordaSerializable
class QueryOracleHandler(private val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING : ProgressTracker.Step("Received stock to provide the spot price for.")
        object RETRIEVING : ProgressTracker.Step("Retrieving the spot price.")
        object SENDING : ProgressTracker.Step("Sending spot price to counterparty.")
    }

    override val progressTracker = ProgressTracker(RECEIVING, RETRIEVING, SENDING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING
        val instrument = counterpartySession.receive<String>().unwrap { it }

        progressTracker.currentStep = RETRIEVING
        val spotPrice = serviceHub.cordaService(Oracle::class.java).querySpot(instrument)

        progressTracker.currentStep = SENDING
        counterpartySession.send(spotPrice)
    }
}