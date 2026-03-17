package org.klaud

import android.os.Environment
import android.os.StatFs

object StorageHelper {
    fun getAvailableBytes(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    fun getTotalBytes(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.blockCountLong * stat.blockSizeLong
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
        else                    -> "%.0f KB".format(bytes / 1_000.0)
    }

    fun hasEnoughSpace(requiredBytes: Long): Boolean =
        getAvailableBytes() >= requiredBytes + 50_000_000L
}
