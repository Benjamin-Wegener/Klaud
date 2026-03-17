package org.klaud.debug

import android.content.Context
import org.klaud.PairingManager
import java.io.File

fun exportPairingData(context: Context) {
    val data = PairingManager.getInstance(context).getOwnPairingData()
    File(context.getExternalFilesDir(null), "pairing.json")
        .writeText("""{"onion":"${data.onionAddress}","key":"${data.publicKeyHash}"}""")
}
