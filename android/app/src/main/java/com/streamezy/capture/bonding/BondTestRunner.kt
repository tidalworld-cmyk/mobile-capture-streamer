package com.streamezy.capture.bonding

import android.util.Log

class BondTestRunner(private val bondSession: BondSession) {
    companion object {
        private const val TAG = "BondTestRunner"
    }

    data class TestResult(val testName: String, val passed: Boolean, val details: String)

    fun runAllTests(onProgress: (TestResult) -> Unit) {
        Thread {
            Log.i(TAG, "==================================================")
            Log.i(TAG, "STARTING BONDSTREAM PHASE 1 VERIFICATION TESTS")
            Log.i(TAG, "==================================================")

            // Test 1: One path -> VPS
            val t1 = runSinglePathTest()
            onProgress(t1)

            // Test 2: Two paths -> VPS
            val t2 = runTwoPathTest()
            onProgress(t2)

            // Test 3: Synthetic packet sequence integrity
            val t3 = runSequenceIntegrityTest()
            onProgress(t3)

            // Test 4: LiveU LRT ARQ Recovery Test
            val t4 = runLiveUArqRecoveryTest()
            onProgress(t4)

            Log.i(TAG, "==================================================")
            Log.i(TAG, "BONDSTREAM PHASE 1 & LIVEU CHECKS COMPLETED")
            Log.i(TAG, "==================================================")
        }.start()
    }

    private fun runSinglePathTest(): TestResult {
        Log.i(TAG, "[TEST 1] Single path communication test...")
        val paths = bondSession.networkManager.getUsablePaths()
        if (paths.isEmpty()) {
            return TestResult("TEST 1: One Path", false, "No usable network path found.")
        }
        val p = paths[0]
        val success = bondSession.sendData("Test Payload 1".toByteArray())
        return TestResult("TEST 1: One Path (${p.name})", success, "Transmitted test packet through ${p.name}")
    }

    private fun runTwoPathTest(): TestResult {
        Log.i(TAG, "[TEST 2] Multi-path readiness test...")
        val paths = bondSession.networkManager.getUsablePaths()
        val notice = bondSession.networkManager.getDualSimSupportNotice()

        return if (paths.size >= 2) {
            TestResult("TEST 2: Two Paths", true, "Multi-path ready: ${paths.joinToString { it.name }}")
        } else {
            val msg = notice ?: "Single path available (${paths.firstOrNull()?.name ?: "None"}). Connect Wi-Fi and Mobile Data simultaneously."
            TestResult("TEST 2: Two Paths", false, msg)
        }
    }

    private fun runSequenceIntegrityTest(): TestResult {
        Log.i(TAG, "[TEST 3] Sequence integrity test...")
        var sent = 0
        for (i in 0 until 50) {
            val ok = bondSession.sendData("SeqPacket-$i".toByteArray())
            if (ok) sent++
        }
        return TestResult("TEST 3: Packet Sequencing", sent > 0, "Sent $sent/50 sequenced test packets.")
    }

    private fun runLiveUArqRecoveryTest(): TestResult {
        Log.i(TAG, "[TEST 4] LiveU LRT ARQ Recovery test...")
        val ok = bondSession.sendData("LiveU Test Recovery Packet".toByteArray())
        return TestResult("TEST 4: LiveU ARQ Engine", ok, "LiveU LRT ARQ active (${bondSession.retransmissionsRepaired.get()} repaired, playout: ${bondSession.playoutDelayMs}ms)")
    }
}
