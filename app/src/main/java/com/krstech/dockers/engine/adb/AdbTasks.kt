package com.krstech.dockers.engine.adb

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A shell-user task, Shizuku style: one tap runs a privileged command. */
@Serializable
data class AdbTask(
    val id: String,
    val title: String,
    val description: String = "",
    val command: String,
    val minSdk: Int = 0,
    val builtIn: Boolean = false,
)

object AdbTasks {
    fun builtIn(pkg: String): List<AdbTask> = listOf(
        AdbTask(
            "keep_vm_alive", "Keep Docker VM alive",
            "Turns off Android's phantom process killer so the Docker VM isn't killed in the background (Android 12+).",
            "device_config set_sync_disabled_for_tests persistent 2>/dev/null; " +
                "device_config put activity_manager max_phantom_processes 2147483647; " +
                "settings put global settings_enable_monitor_phantom_procs false; " +
                "echo \"max_phantom_processes = $(device_config get activity_manager max_phantom_processes)\"; " +
                "echo \"monitor_phantom_procs = $(settings get global settings_enable_monitor_phantom_procs)\"",
            minSdk = 31, builtIn = true,
        ),
        AdbTask(
            "background", "Unrestricted background running",
            "Adds KRS Dockers to the battery-optimization allowlist and allows it to run in the background.",
            "dumpsys deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; cmd appops set $pkg RUN_IN_BACKGROUND allow; echo done",
            builtIn = true,
        ),
        AdbTask(
            "notifications", "Allow notifications",
            "Grants the notification permission (needed for the engine and pairing notifications).",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS && echo granted",
            minSdk = 33, builtIn = true,
        ),
        AdbTask(
            "device_info", "Device & kernel info",
            "Model, Android version, kernel, ABI, memory, storage and whether /dev/kvm exists.",
            "echo \"Model:   $(getprop ro.product.manufacturer) $(getprop ro.product.model)\"; " +
                "echo \"Android: $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))\"; " +
                "echo \"Kernel:  $(uname -r)\"; echo \"ABI:     $(getprop ro.product.cpu.abi)\"; " +
                "grep -E 'MemTotal|MemAvailable' /proc/meminfo; df -h /data | tail -n 1; ls -l /dev/kvm 2>&1",
            builtIn = true,
        ),
        AdbTask(
            "processes", "Top processes",
            "CPU and memory use of running processes (like Docker Desktop's resource view).",
            "top -b -n 1 -m 25 2>/dev/null || ps -A -o PID,USER,%CPU,RSS,NAME | head -n 40",
            builtIn = true,
        ),
        AdbTask(
            "app_logs", "KRS Dockers logs",
            "Recent logcat output of this app, for troubleshooting.",
            "logcat -d -t 300 --pid=$(pidof $pkg | cut -d' ' -f1) 2>&1 | tail -n 300",
            builtIn = true,
        ),
        AdbTask(
            "undo_keep_alive", "Restore phantom process killer",
            "Reverts \"Keep Docker VM alive\" to Android's defaults.",
            "device_config set_sync_disabled_for_tests none 2>/dev/null; device_config delete activity_manager max_phantom_processes; " +
                "settings delete global settings_enable_monitor_phantom_procs; echo restored",
            minSdk = 31, builtIn = true,
        ),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun loadCustom(context: Context): List<AdbTask> =
        context.getSharedPreferences("adb", Context.MODE_PRIVATE).getString("custom_tasks", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(AdbTask.serializer()), it) }.getOrNull() }.orEmpty()

    fun saveCustom(context: Context, tasks: List<AdbTask>) {
        context.getSharedPreferences("adb", Context.MODE_PRIVATE).edit()
            .putString("custom_tasks", json.encodeToString(ListSerializer(AdbTask.serializer()), tasks)).apply()
    }
}
