package dev.tvsopen.transport

import dev.tvsopen.protocol.VehicleModel

/**
 * Pure connection policy shared by the ride service and JVM tests.
 *
 * Companion-device presence can fire while a manual or scheduled connection
 * attempt is already in progress. A second GATT client for the same vehicle
 * is unnecessary and can race the first client during service discovery.
 */
internal object RideConnectionPolicy {

    fun isDuplicateActiveConnection(
        currentAddress: String?,
        currentModel: VehicleModel,
        currentState: ConnectionState?,
        requestedAddress: String,
        requestedModel: VehicleModel,
    ): Boolean {
        val linkIsActive = currentState == ConnectionState.SCANNING ||
            currentState == ConnectionState.CONNECTING ||
            currentState == ConnectionState.CONNECTED

        return linkIsActive &&
            currentAddress?.equals(requestedAddress, ignoreCase = true) == true &&
            currentModel == requestedModel
    }
}
