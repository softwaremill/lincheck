/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.*

/**
 * This base class for managed strategies helps to handle code locations,
 * support locks and waits, and log events
 */
internal abstract class ManagedStrategyBase(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        protected val verifier: Verifier,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        private val testCfg: ManagedCTestConfiguration
) : ManagedStrategy(testClass, scenario, validationFunctions, stateRepresentation, testCfg.guarantees, testCfg.timeoutMs) {
    protected val parallelActors: List<List<Actor>> = scenario.parallelExecution
    // whether a thread finished all its operations
    private val finished: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // what thread is currently allowed to perform operations
    @Volatile
    protected var currentThread: Int = 0
    // detector of loops (i.e. active locks)
    private lateinit var loopDetector: LoopDetector
    // logger of all events in the execution such as thread switches
    private lateinit var eventCollector: ExecutionEventCollector
    // tracker of acquisitions and releases of monitors
    private lateinit var monitorTracker: MonitorTracker
    // random used for the generation of seeds and the execution tree
    protected val generationRandom = Random(0)
    // random used for the execution
    protected lateinit var random: Random
    // seed for the execution random
    private var executionRandomSeed = 0L
    // is thread suspended
    private val isSuspended: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // the number of blocks that should be ignored by the strategy entered and not left for each thread
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // current actor id for each thread
    protected val currentActorId = IntArray(nThreads)
    // InvocationResult that was observed by the strategy in the execution (e.g. deadlock)
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null
    private val callStackTrace: Array<MutableList<CallStackTraceElement>> = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    private var methodIdentifier = 0

    @Throws(Exception::class)
    abstract override fun runImpl(): LincheckFailure?

    override fun onStart(threadId: Int) {
        awaitTurn(threadId)
    }

    override fun onFinish(threadId: Int) {
        awaitTurn(threadId)
        finished[threadId].set(true)
        eventCollector.finishThread(threadId)
        onNewSwitch(threadId)
        doSwitchCurrentThread(threadId, true)
    }

    override fun onFailure(threadId: Int, e: Throwable) {
        if (suddenInvocationResult == null) // not a forcible execution finish
            suddenInvocationResult = UnexpectedExceptionInvocationResult(e)
    }

    /**
     * Is executed before any thread switch
     */
    protected open fun onNewSwitch(threadId: Int) {}

    override fun beforeSharedVariableRead(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeSharedVariableWrite(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeAtomicMethodCall(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeLockAcquire(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(threadId)) return true
        checkCanHaveObstruction { "At least obstruction freedom required, but a lock found" }
        newSwitchPoint(threadId, codeLocation)
        // check if can acquire required monitor
        if (!monitorTracker.canAcquireMonitor(monitor)) {
            monitorTracker.awaitAcquiringMonitor(threadId, monitor)
            // switch to another thread and wait for a moment the monitor can be acquired
            switchCurrentThread(threadId, SwitchReason.LOCK_WAIT, true)
        }
        // can acquire monitor now. actually does it
        monitorTracker.acquireMonitor(threadId, monitor)

        return false
    }

    override fun beforeLockRelease(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(threadId)) return true
        monitorTracker.releaseMonitor(monitor)
        eventCollector.passCodeLocation(threadId, codeLocation)
        return false
    }

    override fun beforePark(threadId: Int, codeLocation: Int, withTimeout: Boolean): Boolean {
        if (!isTestThread(threadId)) return true
        newSwitchPoint(threadId, codeLocation)
        return false
    }

    override fun afterUnpark(threadId: Int, codeLocation: Int, thread: Any) {
        eventCollector.passCodeLocation(threadId, codeLocation)
    }

    override fun beforeWait(threadId: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(threadId)) return true

        checkCanHaveObstruction { "At least obstruction freedom required but a waiting on monitor found" }
        newSwitchPoint(threadId, codeLocation)
        if (withTimeout) return false // timeouts occur instantly
        monitorTracker.waitMonitor(threadId, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(threadId, SwitchReason.MONITOR_WAIT, true)
        return false
    }

    override fun afterNotify(threadId: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        eventCollector.passCodeLocation(threadId, codeLocation)
    }

    override fun afterCoroutineSuspended(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(true)
        if (runner.canResumeCoroutine(threadId, currentActorId[threadId])) {
            // COROUTINE_SUSPENSION_CODELOCATION, because we do not know the actual code location
            newSwitchPoint(threadId, COROUTINE_SUSPENSION_CODE_LOCATION)
        } else {
            // currently a coroutine suspension  is not supposed to violate obstruction-freedom
            // checkCanHaveObstruction { "At least obstruction freedom required but a loop found" }
            switchCurrentThread(threadId, SwitchReason.SUSPENDED, true)
        }
    }

    override fun beforeCoroutineResumed(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(false)
    }

    override fun enterIgnoredSection(threadId: Int) {
        if (isTestThread(threadId))
            ignoredSectionDepth[threadId]++
    }

    override fun leaveIgnoredSection(threadId: Int) {
        if (isTestThread(threadId))
            ignoredSectionDepth[threadId]--
    }

    override fun beforeMethodCall(threadId: Int, codeLocation: Int) {
        if (isTestThread(threadId))
            callStackTrace[threadId].add(CallStackTraceElement(getLocationDescription(codeLocation), methodIdentifier++))
    }

    override fun afterMethodCall(threadId: Int) {
        if (isTestThread(threadId))
            callStackTrace[threadId].removeAt(callStackTrace[threadId].lastIndex)
    }

    override fun makeStateRepresentation(threadId: Int) {
        if (isTestThread(threadId) && !shouldBeIgnored(threadId))
            eventCollector.makeStateRepresentation(threadId)
    }

    private fun isTestThread(threadId: Int) = threadId < nThreads
    private fun shouldBeIgnored(threadId: Int) = ignoredSectionDepth[threadId] > 0

    /**
     * Create a new switch point, where a thread context switch can occur
     */
    protected fun newSwitchPoint(threadId: Int, codeLocation: Int) {
        if (threadId == nThreads) return // can suspend only test threads
        check(threadId == currentThread)
        if (ignoredSectionDepth[threadId] != 0) return // can not suspend in ignored sections
        awaitTurn(threadId)
        var isLoop = false
        if (loopDetector.newOperation(threadId, codeLocation)) {
            checkCanHaveObstruction { "At least obstruction freedom required, but an active lock found" }
            isLoop = true
        }
        val shouldSwitch = shouldSwitch(threadId) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(threadId, reason)
        }
        eventCollector.passCodeLocation(threadId, codeLocation)
        // continue operation
    }

    /**
     * Returns whether thread should switch at the suspension point
     */
    protected abstract fun shouldSwitch(threadId: Int): Boolean

    /**
     * A regular switch on another thread
     */
    protected fun switchCurrentThread(threadId: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        eventCollector.newSwitch(threadId, reason)
        onNewSwitch(threadId)
        doSwitchCurrentThread(threadId, mustSwitch)
        awaitTurn(threadId)
    }

    protected fun doSwitchCurrentThread(threadId: Int, mustSwitch: Boolean = false) {
        val switchableThreads = switchableThreads(threadId)
        val switchableThreadsCount = switchableThreads.count()
        if (switchableThreadsCount == 0) {
            if (mustSwitch && !finished.all { it.get() }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it].get() && isSuspended[it].get() }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner.hashCode()))
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException()
                }
                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }
        val nextThreadNumber = chooseThread(switchableThreadsCount)
        currentThread = switchableThreads[nextThreadNumber]
    }

    /**
     * Threads to which a thread [threadId] can switch
     */
    protected fun switchableThreads(threadId: Int) = (0 until nThreads).filter { it != threadId && canResume(it) }

    /**
     * Chooses a thread to switch among [switchableThreads] variants
     */
    protected abstract fun chooseThread(switchableThreads: Int): Int

    /**
     * Returns whether the thread could continue its execution
     */
    protected fun canResume(threadId: Int): Boolean {
        var canResume = !finished[threadId].get() && monitorTracker.canResume(threadId)
        if (isSuspended[threadId].get())
            canResume = canResume && runner.canResumeCoroutine(threadId, currentActorId[threadId])
        return canResume
    }

    /**
     * Waits until this thread is allowed to be executed.
     */
    protected fun awaitTurn(threadId: Int) {
        // wait actively until the thread is allow to execute
        while (currentThread != threadId) {
            // finish forcibly if an error occured and we already have an InvocationResult.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException()
            Thread.yield()
        }
    }

    /**
     * Verifies results and if there are incorrect results then re-runs with
     * logging of all thread events.
     */
    protected fun checkResults(results: InvocationResult): LincheckFailure? {
        val events = eventCollector.interleavingEvents()
        // if there an InvocationResult was determined by the Strategy then just ignore the Runner's results
        suddenInvocationResult?.let { return it.toLincheckFailure(scenario, events) }
        when (results) {
            is CompletedInvocationResult -> {
                if (!verifier.verifyResults(scenario, results.results))
                    return IncorrectResultsFailure(scenario, results.results, events)
            }
            else -> {
                return results.toLincheckFailure(scenario, events)
            }
        }

        return null
    }

    /**
     * Runs next invocation with the same [scenario][ExecutionScenario].
     *
     * @return invocation results for each executed actor.
     */
    fun runInvocation(): InvocationResult {
        initializeInvocation()
        return runner.run()
    }

    /**
     * Returns all data to the initial state before invocation.
     */
    protected open fun initializeInvocation() {
        finished.forEach { it.set(false) }
        isSuspended.forEach { it.set(false) }
        executionRandomSeed = generationRandom.nextLong()
        random = Random(executionRandomSeed)
        currentActorId.fill(-1)
        loopDetector = LoopDetector(testCfg.hangingDetectionThreshold)
        monitorTracker = MonitorTracker(nThreads)
        eventCollector = ExecutionEventCollector()
        suddenInvocationResult = null
        ManagedStateHolder.resetState(runner.classLoader)
    }

    private fun checkCanHaveObstruction(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
        }
    }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner.hashCode()))
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
        }
    }

    override fun onActorStart(threadId: Int) {
        currentActorId[threadId]++
    }

    /**
     * Detects loop when visiting a codeLocation too often.
     */
    private class LoopDetector(private val hangingDetectionThreshold: Int) {
        private var lastIThread = Int.MIN_VALUE
        private val operationCounts = mutableMapOf<Int, Int>()

        fun newOperation(threadId: Int, codeLocation: Int): Boolean {
            if (lastIThread != threadId) {
                // if we switched threads then reset counts
                operationCounts.clear()
                lastIThread = threadId
            }
            if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false;
            // increment the number of times that we visited a codelocation
            val count = (operationCounts[codeLocation] ?: 0) + 1
            operationCounts[codeLocation] = count
            // return true if the thread exceeded the maximum number of repetitions that we can have
            return count > hangingDetectionThreshold
        }
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class ExecutionEventCollector {
        private val interleavingEvents = mutableListOf<InterleavingEvent>()

        fun newSwitch(threadId: Int, reason: SwitchReason) {
            interleavingEvents.add(SwitchEvent(threadId, currentActorId[threadId], reason, callStackTrace[threadId].toList()))
            // check livelock after every switch
            checkLiveLockHappened(interleavingEvents.size)
        }

        fun finishThread(threadId: Int) {
            interleavingEvents.add(FinishEvent(threadId))
        }

        fun passCodeLocation(threadId: Int, codeLocation: Int) {
            if (codeLocation != COROUTINE_SUSPENSION_CODE_LOCATION) {
                enterIgnoredSection(threadId)
                interleavingEvents.add(PassCodeLocationEvent(
                        threadId, currentActorId[threadId],
                        getLocationDescription(codeLocation),
                        callStackTrace[threadId].toList()
                ))
                leaveIgnoredSection(threadId)
            }
        }

        fun makeStateRepresentation(threadId: Int) {
            // enter ignored section, because stateRepresentation invokes transformed method with switch points
            enterIgnoredSection(threadId)
            val stateRepresentation = runner.stateRepresentation
            leaveIgnoredSection(threadId)
            interleavingEvents.add(StateRepresentationEvent(threadId, currentActorId[threadId], stateRepresentation))
        }

        fun interleavingEvents(): List<InterleavingEvent> = interleavingEvents
    }

    /**
     * Track operations with monitor (acquire/release, wait/notify) to tell whether a thread can be executed.
     */
    private class MonitorTracker(nThreads: Int) {
        // which monitors are held by test threads
        private val acquiredMonitors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        // which monitor a thread want to acquire (or null)
        private val acquiringMonitor = Array<Any?>(nThreads) { null }
        // whether thread is waiting for notify on the corresponding monitor
        private val needsNotification = BooleanArray(nThreads) { false }

        fun canAcquireMonitor(monitor: Any) = monitor !in acquiredMonitors

        fun acquireMonitor(threadId: Int, monitor: Any) {
            acquiredMonitors.add(monitor)
            acquiringMonitor[threadId] = null
        }

        fun releaseMonitor(monitor: Any) {
            acquiredMonitors.remove(monitor)
        }

        fun canResume(threadId: Int): Boolean {
            val monitor = acquiringMonitor[threadId] ?: return true
            return !needsNotification[threadId] && canAcquireMonitor(monitor)
        }

        fun awaitAcquiringMonitor(threadId: Int, monitor: Any) {
            acquiringMonitor[threadId] = monitor
        }

        fun waitMonitor(threadId: Int, monitor: Any) {
            // TODO: can add spurious wakeups
            check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
            releaseMonitor(monitor)
            needsNotification[threadId] = true
            awaitAcquiringMonitor(threadId, monitor)
        }

        fun notify(monitor: Any) {
            // just notify all. odd threads will have a spurious wakeup
            notifyAll(monitor)
        }

        fun notifyAll(monitor: Any) {
            for (threadId in needsNotification.indices)
                if (acquiringMonitor[threadId] === monitor)
                    needsNotification[threadId] = false
        }
    }
}

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1; // currently the exact place of coroutine suspension is not known