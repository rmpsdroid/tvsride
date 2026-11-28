package dev.tvsopen.transport

import dev.tvsopen.protocol.VehicleModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideConnectionPolicyTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val model = VehicleModel.JUPITER_110

    @Test
    fun sameVehicleWhileConnectingIsIgnored() {
        assertTrue(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.CONNECTING, address, model,
            ),
        )
    }

    @Test
    fun sameVehicleWhileConnectedIsIgnored() {
        assertTrue(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.CONNECTED, address, model,
            ),
        )
    }

    @Test
    fun sameVehicleWhileScanningIsIgnored() {
        assertTrue(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.SCANNING, address, model,
            ),
        )
    }

    @Test
    fun disconnectedVehicleCanReconnect() {
        assertFalse(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.DISCONNECTED, address, model,
            ),
        )
    }

    @Test
    fun differentAddressCanReplaceActiveConnection() {
        assertFalse(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.CONNECTED, "11:22:33:44:55:66", model,
            ),
        )
    }

    @Test
    fun differentModelCanReplaceActiveConnection() {
        assertFalse(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address, model, ConnectionState.CONNECTED, address, VehicleModel.JUPITER_125,
            ),
        )
    }

    @Test
    fun addressComparisonIsCaseInsensitive() {
        assertTrue(
            RideConnectionPolicy.isDuplicateActiveConnection(
                address.lowercase(), model, ConnectionState.CONNECTING, address, model,
            ),
        )
    }
}
