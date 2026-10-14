package com.krstech.dockers.util

/** Strips ANSI escape sequences so terminal output can be rendered as plain text. */
object Ansi {
    private val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
    private val other = Regex("\u001B[@-Z\\\\-_]|\u001B[()][AB012]|\u001B[=>]")
    private val control = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    fun strip(s: String): String =
        s.replace(osc, "").replace(csi, "").replace(other, "").replace(control, "")
}
