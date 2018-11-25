package com.template

import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant
import com.template.base.SpotPrice
import net.corda.core.contracts.Requirements.using
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*

// *****************
// * Contract Code *
// *****************
class ForwardContract : Contract {
    companion object {
        @JvmStatic
        val FORWARD_CONTRACT_ID = "com.template.ForwardContract"
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
        class SettlePhysical : TypeOnlyCommandData(), Commands
        class SettleCash : TypeOnlyCommandData(), Commands
    }

    private fun keysFromParticipants(forwardState: ForwardState): Set<PublicKey> {
        return forwardState.participants.map {
            it.owningKey
        }.toSet()
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = command.signers.toSet()

        when (command.value) {
            is Commands.Create -> verifyCreate(tx, setOfSigners)
            is Commands.SettlePhysical -> verifySettlePhysical(tx, setOfSigners)
            is Commands.SettleCash -> verifySettleCash(tx, setOfSigners)

            else -> throw IllegalArgumentException("Unknown command")

        }
    }

    private fun verifyCreate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        "No inputs should be consumed when issuing the contract." using (tx.inputStates.isEmpty())
        "Only one output state should be created." using (tx.outputStates.size == 1)
        val output = tx.outputStates.single() as ForwardState // checked single
        "The initiator and acceptor cannot have the same identity." using (output.acceptor != output.initiator)
        "Both initiator and acceptor must sign the transaction." using (signers == keysFromParticipants(output))
    }

    private fun verifySettlePhysical(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val forwardInputs = tx.inputStates.first() as ForwardState // Second is cash issue
        val cashInput = tx.inputsOfType<Cash.State>()
        val forward = tx.outputsOfType<ForwardState>()
        val timestamp = Instant.now()

        // Handle empty and non-empty wallet for input states
        if (cashInput.isEmpty()) {
            "Previous states are consumed" using (tx.inputStates.size == 1)
        } else {
            "Previous states are consumed" using (tx.inputStates.size == 2) // second state is cash issuer
        }

        "There must not be a ForwardState output" using (forward.isEmpty())
        "Must have matured" using (timestamp >= forwardInputs.settlementTimestamp)
        "Both initiator and acceptor must sign the transaction." using (signers == keysFromParticipants(forwardInputs))
    }

    private fun verifySettleCash(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val forwardInputs = tx.inputStates.first() as ForwardState // Second is cash issue
        val cash = tx.outputsOfType<Cash.State>()
        val forward = tx.outputsOfType<ForwardState>()
        val timestamp = Instant.now()

        "Previous states are consumed" using (tx.inputStates.size == 2) // second is cash issue
        "There must not be a ForwardState output" using (forward.isEmpty())
        "Must have matured" using (timestamp >= forwardInputs.settlementTimestamp)
        "There must be output cash." using (cash.isNotEmpty())
        "Both initiator and acceptor must sign the transaction." using (signers == keysFromParticipants(forwardInputs))
    }

    class OracleCommand(val spotPrice: SpotPrice) : CommandData
}

// *********
// * State *
// *********
data class ForwardState(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: Int, val currency: String, val amount: Amount<Currency>,
                        val settlementTimestamp: Instant, val settlementType: String, val paid: Amount<Currency> = Amount(0, amount.token),
                        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState, SchedulableState {

    override val participants get() = listOf(initiator, acceptor)

    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        if (settlementType == "cash") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettleCashFlow", thisStateRef, linearId, this, amount), settlementTimestamp)
        } else if (settlementType == "physical") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettlePhysicalFlow", thisStateRef, linearId), settlementTimestamp)
        }

        return null
    }

    companion object {
        /**
         * Calculate the balance required to pay the acceptor for a cash settlement
         * @param forwardState ForwardState
         * @param oracleResults SpotPrice
         * @param currency String
         * @return Pair(Amount<Currency>, Party?
         */
        fun calculateCash(forwardState: ForwardState, oracleResults: SpotPrice, currency: String): Pair<Amount<Currency>, Party?> {
            if (forwardState.amount.toDecimal() < oracleResults.value) { // Seller owes buyer
                val difference = (oracleResults.value - forwardState.amount.toDecimal()) * BigDecimal(forwardState.instrumentQuantity)
                return Pair(Amount.fromDecimal(difference, Currency.getInstance(currency)), forwardState.initiator)
            } else if (forwardState.amount.toDecimal() > oracleResults.value) { // Buyer owes seller
                val difference = (forwardState.amount.toDecimal() - oracleResults.value) * BigDecimal(forwardState.instrumentQuantity)
                return Pair(Amount.fromDecimal(difference, Currency.getInstance(currency)), forwardState.acceptor)
            }

            return Pair(Amount(0, Currency.getInstance(currency)), null)
        }
    }
}