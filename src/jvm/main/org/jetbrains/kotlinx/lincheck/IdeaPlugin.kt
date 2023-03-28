/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyStateHolder
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy

// This is org.jetbrains.kotlinx.lincheck.IdeaPluginKt class

const val MINIMAL_PLUGIN_VERSION = "0.0.1"

// Invoked by Lincheck after the minimization is applied. DO NOT FORGET TO TURN OFF THE RUNNER TIMEOUTS.
fun testFailed(trace: Array<String>, version: String?, minimalPluginVersion: String) {
}

fun ideaPluginEnabled(): Boolean {
    return false // should be replaced with `true` to debug the failure
}

fun replay(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

fun beforeEvent(eventId: Int, type: String) {
    val strategy = (ManagedStrategyStateHolder.strategy!! as ModelCheckingStrategy)
    strategy.enterIgnoredSection(strategy.currentThreadNumber())
    if (needVisualization()) {
        runCatching {
            val testObject =
                ((ManagedStrategyStateHolder.strategy as ModelCheckingStrategy).runner as ParallelThreadsRunner).testInstance

            val resultArray = createObjectToNumberMap(testObject)
            val threadsMap = createContinuationToThreadMap()

            visualizeInstance(testObject, resultArray, threadsMap)
        }
    }
    strategy.leaveIgnoredSection(strategy.currentThreadNumber())
}

private fun createObjectToNumberMap(testObject: Any): Array<Any> {
    val resultArray = arrayListOf<Any>()

    val numbersMap = traverseTestObject(testObject)
    numbersMap.forEach { (labeledObject, label) -> // getObjectNumbersMap()
        resultArray.add(labeledObject)
        resultArray.add(label)
    }
    return resultArray.toTypedArray()
}

private fun createContinuationToThreadMap(): Array<Any?> {
    val runner =
        (ManagedStrategyStateHolder.strategy as? ModelCheckingStrategy)?.runner as? ParallelThreadsRunner
    val threads = runner?.executor?.threads

    val threadsMap: Array<Any?> = if (threads == null) emptyArray() else {
        val array = arrayOfNulls<Any?>(threads.size * 2)
        for (i in threads.indices) {
            val thread = threads[i]
            array[i * 2] = thread.cont
            array[i * 2 + 1] = thread
        }
        array
    }

    return threadsMap
}


fun visualizeInstance(testObject: Any, numbersArrayMap: Array<Any>, threadsArrayMap: Array<Any?>) {}
fun needVisualization(): Boolean = false // may be replaced with 'true' in plugin

fun onThreadChange() {}
