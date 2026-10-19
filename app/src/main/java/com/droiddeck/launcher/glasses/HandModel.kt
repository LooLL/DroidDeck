package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.runtime.VrModule

internal enum class HandModel(val path: String, val sha256: String) {
    FULL(VrModule.HAND_MODEL, "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"),
    LITE(VrModule.HAND_MODEL_LITE, "173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8");

    companion object {
        val DEFAULT = FULL
    }
}
