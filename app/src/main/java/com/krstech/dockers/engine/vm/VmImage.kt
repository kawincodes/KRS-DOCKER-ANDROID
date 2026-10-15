package com.krstech.dockers.engine.vm

import kotlinx.serialization.Serializable

/** manifest.json shipped inside krs-vm-<arch>.tar (see vm/build-image.sh). */
@Serializable
data class VmManifest(
    val version: Int = 0,
    val arch: String = "",
    val alpine: String = "",
    val kernel: String = "",
    val docker: String = "",
    val console: String = "",
    val cmdline: String = "",
    val diskSize: String = "",
)
