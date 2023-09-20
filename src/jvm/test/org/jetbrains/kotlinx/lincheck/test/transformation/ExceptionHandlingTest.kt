/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest

class ExceptionHandlingTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var counter = 0

    @Operation
    fun inc() = try {
        badMethod()
    } catch(e: Throwable) {
        counter++
        counter++
    }

    private fun badMethod(): Int = error("bad method")

    override fun extractState(): Any = counter
}
