package com.assistant.core.audio.parity

import org.junit.Ignore
import org.junit.Test

/** inv04 §10.1 `EchoDuckTuningTest` (inv04 §4.8). "No timeout" is pinned behaviourally in [EchoDuckerParityTest]. */
@Ignore("A-05")
class EchoDuckTuningTest {
    private val pins = audioPins()

    /** `cffec38`: 600 → 1000 ms; the mic re-armed into the room tail. */
    @Test fun restoreTailIs1000ms() = pins.long("duck.restore_tail_ms", "MIC_RESTORE_TAIL_MS", 1000L)

    @Test fun drainPollIs80ms() = pins.long("duck.drain_poll_ms", "MIC_RESTORE_DRAIN_POLL_MS", 80L)

    /** `2ccee40`: head ≥ written fired while chunks were still being fed. */
    @Test fun writesQuietWindowIs400ms() = pins.long("duck.writes_quiet_ms", "MIC_RESTORE_WRITES_QUIET_MS", 400L)
}
