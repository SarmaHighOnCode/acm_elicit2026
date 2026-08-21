package com.setu.mesh.app.ble

import android.os.ParcelUuid

/**
 * 16-bit UUID 0x5E70 expressed in the Bluetooth base UUID form. Android recognises this shape
 * and encodes it on air as a **2-byte** UUID, which is exactly what the 24-byte budget assumes:
 *
 * ```
 *   31   AD payload in a legacy advertisement
 *  − 3   Flags AD structure         (len + type + data)
 *  − 4   Service Data AD structure  (len + type + UUID16)
 *  ────
 *  = 24  usable
 * ```
 *
 * A randomly generated 128-bit UUID costs 16 bytes on air instead of 2 and the beacon no longer
 * fits. It must stay in the `0000XXXX-0000-1000-8000-00805F9B34FB` base form.
 */
val SETU_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("00005E70-0000-1000-8000-00805F9B34FB")

/** Every SETU beacon is exactly this many bytes. See `docs/PROTOCOL.md` §1–2. */
const val BEACON_SIZE_BYTES = 24
