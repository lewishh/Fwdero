package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.FORWARD_CONTRACT_ID
import com.template.ForwardContract
import com.template.ForwardState
import com.template.base.ORACLE_NAME
import com.template.flows.OracleQuery
import com.template.oracle.Oracle
import firstIdentityByName
import firstNotary
import getStateAndRefByLinearId
import io.netty.handler.codec.rtsp.RtspMethods.SETUP
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.TwoPartyTradeFlow
import java.math.BigDecimal
import java.time.Instant
import java.util.function.Predicate

@InitiatingFlow
@SchedulableFlow
@StartableByRPC
class SettleCashFlow(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
                        val settlementTimestamp: Instant, val settlementType: String, val position: String, val thisStateRef: StateRef,
                     val forwardState: ForwardState) : FlowLogic<SignedTransaction>() {

    companion object {
        object SET_UP : ProgressTracker.Step("Initialising flow.")
        object QUERYING_THE_ORACLE : ProgressTracker.Step("Querying oracle for the current spot price and volatility.")
        object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
        object CHECK_CASH : ProgressTracker.Step("Check cash amount")
        object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
        object WE_SIGN : ProgressTracker.Step("Signing transaction.")
        object ORACLE_SIGNS : ProgressTracker.Step("Requesting oracle signature.")
        object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, BUILDING_THE_TX, CHECK_CASH,
                VERIFYING_THE_TX, WE_SIGN, ORACLE_SIGNS, OTHERS_SIGN, FINALISING)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = SET_UP
        val notary = serviceHub.firstNotary()
        val oracle = serviceHub.networkMapCache.getNodeByLegalName(ORACLE_NAME)?.legalIdentities?.first()
                ?: throw IllegalArgumentException("Requested oracle $ORACLE_NAME not found on network.")

        progressTracker.currentStep = QUERYING_THE_ORACLE
        val spotPrice = subFlow(OracleQuery(oracle, forwardState.instrument, settlementTimestamp))

        progressTracker.currentStep = BUILDING_THE_TX
        val requiredSigners = listOf(forwardState.initiator, forwardState.acceptor).map { it.owningKey }
        val settleCommand = Command(ForwardContract.Commands.Settle(), requiredSigners)
        val oracleCommand = Command(ForwardContract.OracleCommand(spotPrice), oracle.owningKey)

        val builder = TransactionBuilder(notary)
                .addOutputState(forwardState, FORWARD_CONTRACT_ID)
                .addCommand(settleCommand)
                .addCommand(oracleCommand)

        progressTracker.currentStep = CHECK_CASH
        val forwardPrice = ForwardState.calculateCash(forwardState, spotPrice)

        progressTracker.currentStep = VERIFYING_THE_TX
        builder.verify(serviceHub)

        progressTracker.currentStep = WE_SIGN
        val ptx = serviceHub.signInitialTransaction(builder)

        progressTracker.currentStep = ORACLE_SIGNS
        // Only expose single command to network
        val ftx = ptx.buildFilteredTransaction(Predicate {
            it is Command<*>
                    && it.value is ForwardContract.OracleCommand
                    && oracle.owningKey in it.signers
        })

        val oracleSignature = subFlow(OracleRequestSignature(oracle, ftx))
        val ptxWithOracleSig = ptx.withAdditionalSignature(oracleSignature)

        progressTracker.currentStep = OTHERS_SIGN
        val issuerSession = initiateFlow(forwardState.initiator)
        val stx = subFlow(CollectSignaturesFlow(ptxWithOracleSig, listOf(issuerSession), OTHERS_SIGN.childProgressTracker()))

        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
    }
}

@InitiatingFlow
@InitiatedBy(SettleCashFlow::class)
class SettleCashFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val flow = object : SignTransactionFlow(counterpartySession) {
            @Suspendable
            override fun checkTransaction(stx: SignedTransaction) {
                // We check the oracle is a required signer. If so, we can trust the spot price and volatility data.
                val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)
                stx.requiredSigningKeys.contains(oracle.owningKey)
            }
        }

        val stx = subFlow(flow)
        return waitForLedgerCommit(stx.id)
    }
}