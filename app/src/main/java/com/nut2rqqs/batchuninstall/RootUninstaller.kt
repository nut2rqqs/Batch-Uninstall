package com.nut2rqqs.batchuninstall

/**
 * Uses the device's existing su manager (KernelSU/Magisk) to remove an app
 * for Android user 0. This does NOT delete the APK from the read-only system
 * partition; it only uninstalls/hides the package for the current user.
 */
object RootUninstaller {
    data class Result(val success: Boolean, val output: String)

    fun hasRootAccess(): Result {
        return runSuCommand("id")
    }

    fun uninstallForUser(packageName: String): Result {
        // Package names are identifiers, never accept arbitrary shell input.
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            return Result(false, "Invalid package name")
        }
        return runSuCommand("pm uninstall --user 0 $packageName")
    }

    private fun runSuCommand(command: String): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val finished = process.waitFor()
            if (finished != 0) {
                Result(false, output.ifBlank { "su exited with code $finished" })
            } else {
                val success = command == "id" || output.lineSequence().any {
                    it.trim().equals("Success", ignoreCase = true)
                }
                Result(success, output.ifBlank { "No command output" })
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
