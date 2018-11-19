package com.template

import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant
import com.template.base.SpotPrice
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash
import java.lang.reflect.Type
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
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<ForwardContract.Commands>()

        when (command.value) {
            is Commands.Create -> {
                requireThat {
                    "No inputs should be consumed when issuing the contract." using (tx.inputs.isEmpty())
                    "Only one output state should be created." using (tx.outputs.size == 1)
                    val forward = tx.outputStates.single() as ForwardState
                    "A newly issued contract must have a positive amount." using (forward.amount.quantity > 0)
                    "The initiator and acceptor cannot have the same identity." using (forward.acceptor != forward.initiator)
                    "Both initiator and acceptor together only may sign the transaction." using
                            (command.signers.toSet() == forward.participants.map { it.owningKey }.toSet())
                }
            }

            is Commands.Transfer -> requireThat {
                "An IOU transfer transaction should only consume one input state." using (tx.inputs.size == 1)
                "An IOU transfer transaction should only create one output state." using (tx.outputs.size == 1)
                val input = tx.inputStates.single() as ForwardState
                val output = tx.outputStates.single() as ForwardState
                "The borrower, old lender and new lender only must sign an IOU transfer transaction" using
                        (command.signers.toSet() == (input.participants.map { it.owningKey }.toSet() `union`
                                output.participants.map { it.owningKey }.toSet()))
            }

            is Commands.Settle -> {
                requireThat {
                    // Check there is only one group of Forward's and that there is always an input Forward.
                    val forwards = tx.groupStates<ForwardState, UniqueIdentifier> { it.linearId }.single()
                    requireThat { "There must be one input." using (forwards.inputs.size == 1) }

                    // Check there are output cash states.
                    val cash = tx.outputsOfType<Cash.State>()
                    requireThat { "There must be output cash." using (cash.isNotEmpty()) }

                    // Check that the cash is being assigned to us.
                    val inputForward = forwards.inputs.single()
                    val acceptableCash = cash.filter { it.owner == inputForward.initiator }
                    requireThat { "There must be output cash paid to the recipient." using (acceptableCash.isNotEmpty()) }

                    // Sum the cash being sent to us (we don't care about the issuer).
                    val sumAcceptableCash = acceptableCash.sumCash().withoutIssuer()
                    val amountOutstanding = inputForward.amount - inputForward.paid
                    requireThat { "The amount settled cannot be more than the amount outstanding." using (amountOutstanding >= sumAcceptableCash) }

                    // Check to see if we need an output Forward or not.
                    if (amountOutstanding == sumAcceptableCash) {

                        // If the Forward has been fully settled then there should be no Forward output state.
                        requireThat { "There must be no output Forward as it has been fully settled." using (forwards.outputs.isEmpty()) }

                    } else {

                        // If the Forward has been partially settled then it should still exist.
                        requireThat { "There must be one output Forward." using (forwards.outputs.size == 1) }

                        // Check only the paid property changes.
                        val outputForward = forwards.outputs.single()

                        requireThat {
                            "The amount may not change when settling." using (inputForward.amount == outputForward.amount)
                            "The borrower may not change when settling." using (inputForward.acceptor == outputForward.acceptor)
                            "The lender may not change when settling." using (inputForward.initiator == outputForward.initiator)
                        }
                    }
                    requireThat {
                        "Both lender and borrower together only must sign Forward settle transaction." using
                                (command.signers.toSet() == inputForward.participants.map { it.owningKey }.toSet())
                    }
                }
            }

            else -> throw IllegalArgumentException("Command doesn't exist")

            /*is Commands.SettlePhysical -> {
                val inputs = tx.inputsOfType<ForwardState>()
                val input = inputs.single()
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= input.settlementTimestamp)
                    "An ForwardState input is consumed" using (tx.inputsOfType<ForwardState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "The initiator must be signer." using (command.signers.contains(input.initiator.owningKey))
//                    "Destroy contract on completion" using (tx.outputs.isEmpty())
                    "An ForwardState output is created" using (tx.outputsOfType<ForwardState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                }
            }

            is Commands.SettleCash -> {
                val out = tx.outputs.single().data as ForwardState
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= out.settlementTimestamp)
                    "There should be one output state of type ForwardState." using (tx.outputs.size == 1)
                }
            }*/
        }
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

    fun pay(amount: Amount<Currency>) = copy(paid = paid.plus(amount))
    fun withNewLender(newLender: Party) = copy(acceptor = newLender)

    // Defines the scheduled activity to be conducted by the SchedulableState.
    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        if (settlementType == "cash") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettleCashFlow", initiator, acceptor,
                    instrument, instrumentQuantity, paid, settlementTimestamp, settlementType, thisStateRef, this), settlementTimestamp)
        } else if (settlementType == "physical") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettlePhysicalFlow", initiator, acceptor,
                    instrument, instrumentQuantity, paid, settlementTimestamp, settlementType, thisStateRef), settlementTimestamp)
        }

        return null
    }

    /*companion object {
        fun calculateCash(forwardState: ForwardState, oracleResults: SpotPrice): String {
            if (forwardState.paid == oracleResults.value) {
                return "No money owed"
            } else if (forwardState.paid < oracleResults.value) {
                val difference = (oracleResults.value - forwardState.paid) * forwardState.instrumentQuantity
                return "Seller owes Buyer $difference"
            } else if (forwardState.paid > oracleResults.value) {
                val difference = (forwardState.paid - oracleResults.value) * forwardState.instrumentQuantity
                return "Buyer owes Seller $difference"
            }

            return "Not a cash settlement"
        }
    }*/
}