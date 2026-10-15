package com.drtech.tuya

import kotlin.system.exitProcess

/**
 * Diagnostic CLI for a physically owned Tuya 3.4 device.
 *
 *   TUYA_LOCAL_KEY=<16 chars> tuya-lan query <host> <deviceId>
 *   TUYA_LOCAL_KEY=<16 chars> tuya-lan set   <host> <deviceId> <dp> <value> --confirm
 *
 * The key is read only from the environment and is never printed.
 * `query` is read-only; `set` transmits a command and requires --confirm.
 */
fun main(args: Array<String>) {
    val command = args.getOrNull(0)
    val host = args.getOrNull(1)
    val deviceId = args.getOrNull(2)
    if (command == null || host == null || deviceId == null) {
        System.err.println("usage: query <host> <deviceId> | set <host> <deviceId> <dp> <value> --confirm")
        exitProcess(2)
    }
    val key = System.getenv("TUYA_LOCAL_KEY")?.toByteArray(Charsets.UTF_8)
    if (key == null || key.size != 16) {
        System.err.println("Set TUYA_LOCAL_KEY to the device's 16-character local key.")
        exitProcess(2)
    }

    TuyaLanClient(host, deviceId, key).use { client ->
        try {
            client.connect()
            when (command) {
                "query" -> println(MiniJson.write(client.queryDps()))
                "set" -> {
                    val dp = args.getOrNull(3)
                    val raw = args.getOrNull(4)
                    if (dp == null || raw == null || "--confirm" !in args) {
                        System.err.println("set requires <dp> <value> --confirm")
                        exitProcess(2)
                    }
                    val value: Any = when {
                        raw == "true" -> true
                        raw == "false" -> false
                        raw.toLongOrNull() != null -> raw.toLong()
                        else -> raw
                    }
                    println("return code: ${client.setDps(mapOf(dp to value))}")
                    println(MiniJson.write(client.queryDps()))
                }
                else -> {
                    System.err.println("unknown command: $command")
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            System.err.println("${e::class.simpleName}: ${e.message}")
            exitProcess(1)
        }
    }
}
