package dev.aster.vega.android

import androidx.test.platform.app.InstrumentationRegistry

/** Runs a block on the main thread and waits, which every view test here needs. */
internal object InstrumentationRegistryHelper {
  fun onMain(block: () -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  }
}
