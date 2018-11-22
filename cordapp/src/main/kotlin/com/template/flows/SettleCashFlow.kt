package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.ForwardContract
import com.template.ForwardState
import com.template.base.ORACLE_NAME
import firstIdentityByName
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import java.lang.IllegalArgumentException
import java.util.*
import java.util.function.Predicate

@InitiatingFlow
@StartableByRPC
@SchedulableFlow
class SettleCashFlow(private val thisStateRef: StateRef, private val linearId: UniqueIdentifier, private val forwardState: ForwardState,
                     private val amount: Amount<Currency>) : FlowLogic<SignedTransaction>() {
    companion object {
        object SETUP : ProgressTracker.Step("Initialising flow")
        object ORACLE_QUERY : ProgressTracker.Step("Querying oracle for spot price")
        object TRANSACTION_BUILDING : ProgressTracker.Step("Building transaction")
        object ADDING_CASH : ProgressTracker.Step("Adding the cash to the settlement")
        object TRANSACTION_VERIFICATION : ProgressTracker.Step("Verifying transaction")
        object TRANSACTION_SIGNING : ProgressTracker.Step("Signing the transaction")
        object ORACLE_SIGNING : ProgressTracker.Step("Requesting oracle signature")
        object OTHER_SIGNATURES : ProgressTracker.Step("Requesting other signatures") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(SETUP, ORACLE_QUERY, TRANSACTION_BUILDING, ADDING_CASH, TRANSACTION_VERIFICATION,
                TRANSACTION_SIGNING, ORACLE_SIGNING, OTHER_SIGNATURES)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val forwardToSettle = serviceHub.vaultService.queryBy<ForwardState>(queryCriteria).states.single()
        val counterparty = forwardToSettle.state.data.acceptor

        val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)
        val notary = forwardToSettle.state.notary


        val spotPrice = subFlow(OracleQuery(oracle, forwardState.instrument))
        val oracleCommand = Command(ForwardContract.OracleCommand(spotPrice), oracle.owningKey)
        val (requiredPayment, payee) = ForwardState.calculateCash(forwardState, spotPrice, forwardState.currency)

        val settleCommand = Command(ForwardContract.Commands.SettleCash(), listOf(counterparty.owningKey, ourIdentity.owningKey))
        val builder = TransactionBuilder(notary)
                .addCommand(oracleCommand)
                .addCommand(settleCommand)

        if (serviceHub.getCashBalance(amount.token) < requiredPayment) {
            throw IllegalArgumentException("Not enough funds")
        }

        if (payee == forwardState.initiator) {
            // Party A owes Party B
            val (_, cashKeys) = Cash.generateSpend(serviceHub, builder, amount, ourIdentityAndCert, counterparty)

            // Step 8. Verify and sign the transaction.
            builder.verify(serviceHub)

            // We need to sign transaction with all keys referred from Cash input states + our public key
            val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
            val ptx = serviceHub.signInitialTransaction(builder, myKeysToSign)

            val ftx = ptx.buildFilteredTransaction(Predicate {
                it is Command<*>
                        && it.value is ForwardContract.OracleCommand
                        && oracle.owningKey in it.signers
            })

            val oracleSignature = subFlow(OracleRequestSignature(oracle, ftx))
            val ptxWithOracleSig = ptx.withAdditionalSignature(oracleSignature)

            // Initialising session with other party
            val counterpartySession = initiateFlow(counterparty)

            // Step 9. Collecting missing signatures
            val stx = subFlow(CollectSignaturesFlow(ptxWithOracleSig, listOf(counterpartySession), myOptionalKeys = myKeysToSign))

            // Step 10. Finalize the transaction.
            return subFlow(FinalityFlow(stx))
        } else if (payee == forwardState.acceptor) {
            val partyBCert = getPartyBCert()

            // Party B owes Party B
            val (_, cashKeys) = Cash.generateSpend(serviceHub, builder, amount, partyBCert, forwardState.initiator)

            // Step 8. Verify and sign the transaction.
            builder.verify(serviceHub)

            // We need to sign transaction with all keys referred from Cash input states + our public key
            val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
            val ptx = serviceHub.signInitialTransaction(builder, myKeysToSign)

            val ftx = ptx.buildFilteredTransaction(Predicate {
                it is Command<*>
                        && it.value is ForwardContract.OracleCommand
                        && oracle.owningKey in it.signers
            })

            val oracleSignature = subFlow(OracleRequestSignature(oracle, ftx))
            val ptxWithOracleSig = ptx.withAdditionalSignature(oracleSignature)

            // Initialising session with other party
            val counterpartySession = initiateFlow(counterparty)

            // Step 9. Collecting missing signatures
            val stx = subFlow(CollectSignaturesFlow(ptxWithOracleSig, listOf(counterpartySession), myOptionalKeys = myKeysToSign))

            // Step 10. Finalize the transaction.
            return subFlow(FinalityFlow(stx))
        }

        // Step 8. Verify and sign the transaction.
        builder.verify(serviceHub)

        val ptx = serviceHub.signInitialTransaction(builder)

        val ftx = ptx.buildFilteredTransaction(Predicate {
            it is Command<*>
                    && it.value is ForwardContract.OracleCommand
                    && oracle.owningKey in it.signers
        })

        val oracleSignature = subFlow(OracleRequestSignature(oracle, ftx))
        val ptxWithOracleSig = ptx.withAdditionalSignature(oracleSignature)

        // Initialising session with other party
        val counterpartySession = initiateFlow(counterparty)

        // Step 9. Collecting missing signatures
        val stx = subFlow(CollectSignaturesFlow(ptxWithOracleSig, listOf(counterpartySession)))

        // Step 10. Finalize the transaction.
        return subFlow(FinalityFlow(stx))
    }

    private fun getPartyBCert(): PartyAndCertificate {
        val partyBIdentityAndCertList = serviceHub.networkMapCache.getNodeByLegalName(CordaX500Name("PartyB", "New York", "US"))
                ?.legalIdentitiesAndCerts
                ?: throw FlowException("PartyB not found on network.")
        return partyBIdentityAndCertList.first()
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