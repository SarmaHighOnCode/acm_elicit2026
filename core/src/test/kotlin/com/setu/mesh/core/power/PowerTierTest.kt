package com.setu.mesh.core.power

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PowerTierTest {

    @Test
    fun `forBattery boundaries`() {
        assertEquals(PowerTier.BRIDGE, PowerTier.forBattery(60, false))
        assertEquals(PowerTier.BRIDGE, PowerTier.forBattery(100, false))
        
        assertEquals(PowerTier.RELAY, PowerTier.forBattery(30, false))
        assertEquals(PowerTier.RELAY, PowerTier.forBattery(59, false))
        
        assertEquals(PowerTier.GOSSIP, PowerTier.forBattery(15, false))
        assertEquals(PowerTier.GOSSIP, PowerTier.forBattery(29, false))
        
        assertEquals(PowerTier.FLARE, PowerTier.forBattery(5, false))
        assertEquals(PowerTier.FLARE, PowerTier.forBattery(14, false))
        
        assertEquals(PowerTier.EMBER, PowerTier.forBattery(4, false))
        assertEquals(PowerTier.EMBER, PowerTier.forBattery(0, false))
    }

    @Test
    fun `charging always returns BRIDGE`() {
        assertEquals(PowerTier.BRIDGE, PowerTier.forBattery(4, true))
        assertEquals(PowerTier.BRIDGE, PowerTier.forBattery(100, true))
        assertEquals(PowerTier.BRIDGE, PowerTier.forBattery(0, true))
    }
}
