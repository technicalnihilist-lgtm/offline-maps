package com.offlinemaps.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which style-load callback is allowed to attach (OPTIMIZATION.md H-05).
 *
 * A rebuild issues a token and only the callback carrying the token that is still pending may
 * attach, so a superseded load installs nothing, a repeated callback installs nothing twice, and a
 * callback that lands after the controller is destroyed is inert. Without that, a rebuild could
 * leave the overlays on a style the map no longer shows - the "old sources and layers alive" case.
 */
class StyleSwapGateTest {

    @Test
    fun `nothing is attachable before the first rebuild`() {
        val gate = StyleSwapGate()
        assertFalse(gate.attach(0L))
        assertFalse(gate.attach(1L))
    }

    @Test
    fun `the token of the rebuild that is pending attaches`() {
        val gate = StyleSwapGate()
        val token = gate.beginSwap()
        assertTrue(gate.attach(token))
    }

    @Test
    fun `a second rebuild supersedes the first`() {
        val gate = StyleSwapGate()
        val first = gate.beginSwap()
        val second = gate.beginSwap()
        assertFalse("the superseded load must not attach", gate.attach(first))
        assertTrue(gate.attach(second))
    }

    @Test
    fun `the superseded callback still cannot attach after the newer one landed`() {
        val gate = StyleSwapGate()
        val first = gate.beginSwap()
        val second = gate.beginSwap()
        assertTrue(gate.attach(second))
        assertFalse(gate.attach(first))
    }

    @Test
    fun `the same callback cannot attach twice`() {
        val gate = StyleSwapGate()
        val token = gate.beginSwap()
        assertTrue(gate.attach(token))
        assertFalse(gate.attach(token))
    }

    @Test
    fun `a callback that arrives after detach installs nothing`() {
        val gate = StyleSwapGate()
        val token = gate.beginSwap()
        gate.detach()
        assertFalse(gate.attach(token))
    }

    @Test
    fun `a rebuild after detach works again`() {
        val gate = StyleSwapGate()
        gate.beginSwap()
        gate.detach()
        val next = gate.beginSwap()
        assertTrue(gate.attach(next))
    }

    @Test
    fun `tokens are never reused across rebuilds`() {
        val gate = StyleSwapGate()
        val seen = HashSet<Long>()
        repeat(64) {
            val token = gate.beginSwap()
            assertTrue("token $token was issued twice", seen.add(token))
            assertTrue(gate.attach(token))
        }
        assertEquals(64, seen.size)
    }
}
