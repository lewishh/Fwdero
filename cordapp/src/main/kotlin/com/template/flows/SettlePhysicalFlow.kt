package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.ForwardContract
import com.template.ForwardState
import com.template.base.ORACLE_NAME
import firstIdentityByName
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.Obligation
import net.corda.finance.contracts.getCashBalance
import java.lang.IllegalArgumentException
import java.util.*

@InitiatingFlow
@StartableByRPC
@SchedulableFlow
/**
 * Settle physical based forward contracts using the input state. No output state is created if successful.
 * @param thisStateRef StateRef
 * @param linearId UniqueIdentifier
 * @param amount Amount<Currency>
 *
 * @return FlowLogic<SignedTransaction>
 */
class SettlePhysicalFlow(private val thisStateRef: StateRef, private val linearId: UniqueIdentifier, private var amount: Amount<Currency>): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val forwardToSettle = serviceHub.vaultService.queryBy<ForwardState>(queryCriteria).states.single()
        val counterparty = forwardToSettle.state.data.acceptor

        val notary = forwardToSettle.state.notary
        val settleCommand = Command(ForwardContract.Commands.SettlePhysical(), listOf(counterparty.owningKey, ourIdentity.owningKey))

        val builder = TransactionBuilder(notary = notary)
        builder.addInputState(forwardToSettle)
                .addCommand(settleCommand)

        val (_, cashKeys) = Cash.generateSpend(serviceHub, builder, amount, ourIdentityAndCert, counterparty)

        builder.verify(serviceHub)

        val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
        val ptx = serviceHub.signInitialTransaction(builder, myKeysToSign)

        val counterpartySession = initiateFlow(counterparty)

        val stx = subFlow(CollectSignaturesFlow(ptx, listOf(counterpartySession), myOptionalKeys = myKeysToSign))

        return subFlow(FinalityFlow(stx))
    }
}

/**
 * Handles signing of physical based settlements
 * @param flowSession FlowSession
 */
@InitiatingFlow
@InitiatedBy(SettlePhysicalFlow::class)
class SettlePhysicalFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val flow = object : SignTransactionFlow(counterpartySession) {
            @Suspendable
            override fun checkTransaction(stx: SignedTransaction) {
                // No checks
            }
        }

        val stx = subFlow(flow)
        return waitForLedgerCommit(stx.id)
    }
}