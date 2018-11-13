package com.template.base

import net.corda.core.identity.CordaX500Name
import java.math.BigDecimal

val ORACLE_NAME = CordaX500Name("Oracle", "New York","US")
val INSTRUMENT = "Robusta Coffee"
val INSTRUMENT_PRICE = BigDecimal(1.17)

val KNOWN_SPOTS = listOf(
        SpotPrice(INSTRUMENT, INSTRUMENT_PRICE)
)