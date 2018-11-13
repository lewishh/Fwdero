package com.template.base
import net.corda.core.serialization.CordaSerializable
import java.math.BigDecimal

/** Represents the price of a given instrument at a given point in time. */
@CordaSerializable
data class SpotPrice(val instrument: String, val value: BigDecimal)