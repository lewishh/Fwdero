package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.FORWARD_CONTRACT_ID
import com.template.ForwardContract
import com.template.ForwardState
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
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
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing settlement transaction with our private key (ForwardSettleFlow)")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Recording settlement transaction (ForwardSettleFlow)") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        progressTracker.currentStep = GENERATING_TRANSACTION
        val input = serviceHub.toStateAndRef<ForwardState>(thisStateRef)
        val output = ForwardState(initiator, acceptor, instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position)
        val settleCommand = Command(ForwardContract.Commands.SettlePhysical(), ourIdentity.owningKey)
        val txBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(input)
                .addOutputState(output, FORWARD_CONTRACT_ID)
                .addCommand(settleCommand)

        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))
        // return "Seller: physically deliver the asset, Buyer: pay for the asset"
    }
}

// Define InitiateFlowResponder:
@InitiatedBy(SettlePhysicalFlow::class)
class SettlePhysicalFlowResponder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Forward transaction." using (output is ForwardState)
            }
        }

        return subFlow(signTransactionFlow)
    }
}