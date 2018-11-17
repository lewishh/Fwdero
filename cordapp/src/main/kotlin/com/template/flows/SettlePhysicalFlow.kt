package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.FORWARD_CONTRACT_ID
import com.template.ForwardContract
import com.template.ForwardState
import firstNotary
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.math.BigDecimal
import java.time.Instant

@InitiatingFlow
@SchedulableFlow
@StartableByRPC
class SettlePhysicalFlow(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal,
                         val deliveryPrice: BigDecimal, val settlementTimestamp: Instant, val settlementType: String, val position: String,
                     val thisStateRef: StateRef) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Updating Forward transaction for settlement process (ForwardSettleFlow)")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing settlement transaction with our private key (ForwardSettleFlow)") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Recording settlement transaction (ForwardSettleFlow)") {
            override fun childProgressTracker() = FinalityFlow.tracker()
            }
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = GENERATING_TRANSACTION
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val input = serviceHub.toStateAndRef<ForwardState>(thisStateRef)
//        val outputState = ForwardState(initiator, acceptor, instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position)
        val settleCommand = Command(ForwardContract.Commands.SettlePhysical(), listOf(initiator.owningKey, acceptor.owningKey)) // this
        val txBuilder = TransactionBuilder(notary)
                .addInputState(input) // add current state
//                .addOutputState(outputState, FORWARD_CONTRACT_ID) // create new state
                .addCommand(settleCommand) // add initiator and command keys

        // We sign the transaction.
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // We finalise the transaction.
        progressTracker.currentStep = FINALISING_TRANSACTION
        subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))
        return signedTx
    }
}