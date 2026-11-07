package com.blacklab.app.vehicle

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build

/**
 * Hears the phone connect to and disconnect from any Bluetooth device and starts or ends a drive when
 * it's the car. ACL broadcasts are sent only by the system Bluetooth stack (protected broadcasts), so
 * exporting this receiver doesn't let other apps trigger it.
 */
class CarLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> VehicleLink.onBluetoothConnected(context, device)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> VehicleLink.onBluetoothDisconnected(context, device.address)
        }
    }
}

/**
 * Companion-device presence: once the car is paired with BlackLab through Android's companion
 * device dialog, the system wakes BlackLab when the car connects — even if BlackLab hasn't run in a
 * while — and allows the drive to start in the background.
 */
class CarCompanionService : CompanionDeviceService() {
    private fun address(id: Int): String? = runCatching {
        getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == id }?.deviceMacAddress?.toString()
    }.getOrNull()

    @androidx.annotation.RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val addr = address(event.associationId) ?: return
        val link = VehicleLink.load(this).link
        if (!link.autoTrips || !addr.equals(link.carAddress, true)) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> VehicleLink.start(this, "Car Bluetooth", link.carName)
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> VehicleLink.stop(this, "Car Bluetooth")
        }
    }

    @Deprecated("Android 15 path; Android 16+ uses onDevicePresenceEvent")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        // On Android 15 this also fires for BLE range; the ACL receiver handles the actual connection
    }
}

/** Pairs the car with BlackLab as a companion device (one system dialog) and starts presence observing. */
object CarCompanion {
    fun associate(activity: Activity, address: String, launch: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return onError("This phone doesn't support companion devices")
        val req = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        cdm.associate(req, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
            override fun onAssociationCreated(info: AssociationInfo) = observe(activity, info)
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing was cancelled")
        })
    }

    fun observe(context: Context, info: AssociationInfo) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            else @Suppress("DEPRECATION") cdm.startObservingDevicePresence(info.deviceMacAddress.toString())
        }
        VehicleLink.setLink(context) { it.copy(associationId = info.id) }
    }

    /** Keeps observing after an app update or reboot (the association survives, the observation might not). */
    fun refresh(context: Context) {
        val id = VehicleLink.load(context).link.associationId
        if (id < 0) return
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching { cdm.myAssociations.firstOrNull { it.id == id } }.getOrNull()?.let { observe(context, it) }
            ?: VehicleLink.setLink(context) { it.copy(associationId = -1) }
    }

    fun paired(context: Context): Boolean {
        val l = VehicleLink.load(context).link
        if (l.associationId < 0) return false
        return runCatching { context.getSystemService(CompanionDeviceManager::class.java).myAssociations.any { it.id == l.associationId } }.getOrDefault(false)
    }

    fun unpair(context: Context) {
        val id = VehicleLink.load(context).link.associationId
        if (id >= 0) runCatching { context.getSystemService(CompanionDeviceManager::class.java).disassociate(id) }
        VehicleLink.setLink(context) { it.copy(associationId = -1) }
    }
}
