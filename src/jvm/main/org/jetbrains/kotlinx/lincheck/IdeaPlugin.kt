/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

// This is org.jetbrains.kotlinx.lincheck.IdeaPluginKt class

const val MINIMAL_PLUGIN_VERSION = "0.0.1"

// Invoked by Lincheck after the minimization is applied
fun testFailed(trace: Array<String>, version: String?, minimalPluginVersion: String) {
}

fun isDebuggerTestMode() = System.getProperty("lincheck.debug.test") != null

fun ideaPluginEnabled(): Boolean { // should be replaced with `true` to debug the failure
    // treat as enabled in tests
    return isDebuggerTestMode()
        .also {
            if (it) println("Run Lincheck test with before events")
        }
}

fun replay(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

fun beforeEvent(eventId: Int, type: String) {
    if (needVisualization()) {
        // Not implemented yet
        // visualizeInstance(...)
    }
}

fun visualizeInstance(s: String) {}
fun needVisualization(): Boolean = false // may be replaced with 'true' in plugin

fun onThreadChange() {}
