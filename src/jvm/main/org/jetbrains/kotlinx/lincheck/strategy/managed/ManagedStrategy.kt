/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.util.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import sun.nio.ch.lincheck.*
import java.lang.invoke.*
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

/**
 * This is an abstraction for all managed strategies, which encapsulated
 * the required byte-code transformation and [running][Runner] logic and provides
 * a high-level level interface to implement the strategy logic.
 *
 * It is worth noting that here we also solve all the transformation
 * and class loading problems.
 */
abstract class ManagedStrategy(
    private val testClass: Class<*>,
    scenario: ExecutionScenario,
    private val verifier: Verifier,
    private val validationFunctions: List<Method>,
    private val stateRepresentationFunction: Method?,
    private val testCfg: ManagedCTestConfiguration
) : Strategy(scenario), SharedEventsTracker {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.parallelExecution.size
    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    private val runner = createRunner()

    // == EXECUTION CONTROL FIELDS ==

    // Which thread is allowed to perform operations?
    @Volatile
    protected var currentThread: Int = 0
    // Which threads finished all the operations?
    private val finished = BooleanArray(nThreads) { false }
    // Which threads are suspended?
    private val isSuspended = BooleanArray(nThreads) { false }
    // Current actor id for each thread.
    protected val currentActorId = IntArray(nThreads)
    // Detector of loops or hangs (i.e. active locks).
    private lateinit var loopDetector: LoopDetector
    // Tracker of acquisitions and releases of monitors.
    private lateinit var monitorTracker: MonitorTracker

    // InvocationResult that was observed by the strategy during the execution (e.g., a deadlock).
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // Whether additional information requires for the trace construction should be collected.
    private var collectTrace = false
    // Collector of all events in the execution such as thread switches.
    private var traceCollector: TraceCollector? = null // null when `collectTrace` is false
    // Stores the currently executing methods call stack for each thread.
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    // Stores the global number of method calls.
    private var methodCallNumber = 0
    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    private val suspendedFunctionsStack = Array(nThreads) { mutableListOf<Int>() }

    private fun createRunner() = ManagedStrategyRunner(
        managedStrategy = this,
        testClass = testClass,
        validationFunctions = validationFunctions,
        stateRepresentationMethod = stateRepresentationFunction,
        timeoutMs = testCfg.timeoutMs,
        useClocks = UseClocks.ALWAYS
    )

    override fun run(): LincheckFailure? = runImpl().also { close() }

    // == STRATEGY INTERFACE METHODS ==

    /**
     * This method implements the strategy logic.
     */
    protected abstract fun runImpl(): LincheckFailure?

    /**
     * This method is invoked before every thread context switch.
     * @param iThread current thread that is about to be switched
     * @param mustSwitch whether the switch is not caused by strategy and is a must-do (e.g, because of monitor wait)
     */
    protected open fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {}

    /**
     * Returns whether thread should switch at the switch point.
     * @param iThread the current thread
     */
    protected abstract fun shouldSwitch(iThread: Int): Boolean

    /**
     * Choose a thread to switch from thread [iThread].
     * @return id the chosen thread
     */
    protected abstract fun chooseThread(iThread: Int): Int

    /**
     * Returns all data to the initial state.
     */
    protected open fun initializeInvocation() {
        finished.fill(false)
        isSuspended.fill(false)
        currentActorId.fill(-1)
        loopDetector = LoopDetector(testCfg.hangingDetectionThreshold)
        monitorTracker = MonitorTracker(nThreads)
        traceCollector = if (collectTrace) TraceCollector() else null
        suddenInvocationResult = null
        callStackTrace.forEach { it.clear() }
        suspendedFunctionsStack.forEach { it.clear() }
        randoms.forEachIndexed { i, r -> r.setSeed(i + 239L) }
        (runner as ParallelThreadsRunner).executor.threads.forEach {
            it.sharedEventsTracker = this
        }
        localObjectManager = LocalObjectManager()
    }

    // == BASIC STRATEGY METHODS ==

    /**
     * Checks whether the [result] is a failing one or is [CompletedInvocationResult]
     * but the verification fails, and return the corresponding failure.
     * Returns `null` if the result is correct.
     */
    protected fun checkResult(result: InvocationResult): LincheckFailure? = when (result) {
        is CompletedInvocationResult -> {
            if (verifier.verifyResults(scenario, result.results)) null
            else IncorrectResultsFailure(scenario, result.results, collectTrace(result))
        }
        else -> result.toLincheckFailure(scenario, collectTrace(result))
    }

    /**
     * Re-runs the last invocation to collect its trace.
     */
    private fun collectTrace(failingResult: InvocationResult): Trace? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectTrace = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
            failingResult is CompletedInvocationResult -> true
            failingResult is ValidationFailureInvocationResult -> true
            else -> false
        }

        if (!canCollectTrace) {
            // Interleaving events can be collected almost always,
            // except for the strange cases such as Runner's timeout or exceptions in LinCheck.
            return null
        }
        collectTrace = true
        val loggedResults = runInvocation()
        val sameResultTypes = loggedResults.javaClass == failingResult.javaClass
        val sameResults = loggedResults !is CompletedInvocationResult || failingResult !is CompletedInvocationResult || loggedResults.results == failingResult.results
        check(sameResultTypes && sameResults) {
            StringBuilder().apply {
                appendLine("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendLine("== Reporting the first execution without execution trace ==")
                appendLine(failingResult.toLincheckFailure(scenario, null))
                appendLine("== Reporting the second execution ==")
                appendLine(loggedResults.toLincheckFailure(scenario, Trace(traceCollector!!.trace)).toString())
            }.toString()
        }
        return Trace(traceCollector!!.trace)
    }

    /**
     * Runs the next invocation with the same [scenario][ExecutionScenario].
     */
    protected fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result = runner.run()
        // Has strategy already determined the invocation result?
        suddenInvocationResult?.let { return it  }
        return result
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !curActorIsBlocking && !concurrentActorCausesBlocking) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    private val curActorIsBlocking: Boolean
        get() = scenario.parallelExecution[currentThread][currentActorId[currentThread]].blocking

    private val concurrentActorCausesBlocking: Boolean
        get() = currentActorId.mapIndexed { iThread, actorId ->
                    if (iThread != currentThread && !finished[iThread])
                        scenario.parallelExecution[iThread][actorId]
                    else null
                }.filterNotNull().any { it.causesBlocking }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    override fun close() {
        runner.close()
    }

    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (suddenInvocationResult != null) return
        check(iThread == currentThread)
        var isLoop = false
        if (loopDetector.visitCodeLocation(iThread, codeLocation)) {
            failIfObstructionFreedomIsRequired {
                // Log the last event that caused obstruction freedom violation
                traceCollector?.passCodeLocation(tracePoint)
                "Obstruction-freedom is required but an active lock has been found"
            }
            checkLiveLockHappened(loopDetector.totalOperations)
            isLoop = true
        }
        if (suddenInvocationResult != null) return
        val shouldSwitch = shouldSwitch(iThread) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(iThread, reason)
        }
        traceCollector?.passCodeLocation(tracePoint)
        // continue the operation
    }

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onStart(iThread: Int) {
        awaitTurn(iThread)
    }

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onFinish(iThread: Int) {
        (Thread.currentThread() as TestThread).inTestingCode = false
        traceCollector?.addStateRepresentation()
        awaitTurn(iThread)
        finished[iThread] = true
        traceCollector?.finishThread(iThread)
        doSwitchCurrentThread(iThread, true)
    }

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        (Thread.currentThread() as TestThread).inTestingCode = false
        // Despite the fact that the corresponding failure will be detected by the runner,
        // the managed strategy can construct a trace to reproduce this failure.
        // Let's then store the corresponding failing result and construct the trace.
        if (exception === ForcibleExecutionFinishException) return // not a forcible execution finish
        suddenInvocationResult = UnexpectedExceptionInvocationResult(exception)
    }

    override fun onActorStart(iThread: Int) {
        val t = (Thread.currentThread() as TestThread)
        t.inTestingCode = false
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedFunctionsStack[iThread].clear()
        loopDetector.reset(iThread)
        t.inTestingCode = true
    }

    /**
     * Returns whether the specified thread is active and
     * can continue its execution (i.e. is not blocked/finished).
     */
    private fun isActive(iThread: Int): Boolean {
        return  !finished[iThread] &&
                !monitorTracker.isWaiting(iThread) &&
                !(isSuspended[iThread] && !runner.isCoroutineResumed(iThread, currentActorId[iThread]))
    }

    /**
     * Waits until the specified thread can continue
     * the execution according to the strategy decision.
     */
    private fun awaitTurn(iThread: Int) {
        // Wait actively until the thread is allowed to continue
        var curThread = currentThread
        var i = 0
        while (curThread != iThread) {
            // Finish forcibly if an error occurred and we already have an `InvocationResult`.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException
//            val t = (runner as ParallelThreadsRunner).executor.threads[curThread]
//            check(t.iThread == curThread)
//            if (t.state == Thread.State.BLOCKED) {
//                synchronized(this) {
//                    if (curThread == currentThread) {
//                        // We need a deterministic wait to decide
//                        switchCurrentThread(curThread, SwitchReason.STRATEGY_SWITCH, true)
//                    }
//                }
//            }
//            Thread.yield()
            if (++i % SPINNING_LOOP_ITERATIONS_BEFORE_YIELD == 0) Thread.yield()
            curThread = currentThread
        }
    }

    override fun shouldAnalyzeCurrentThread(): Boolean =
        Thread.currentThread() in (runner as ParallelThreadsRunner).executor.threads


    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        traceCollector?.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
        awaitTurn(iThread)
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        onNewSwitch(iThread, mustSwitch)
        val switchableThreads = switchableThreads(iThread)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it] && isSuspended[it] }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException
                }
                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }
        val nextThreadId = chooseThread(iThread)
        currentThread = nextThreadId
    }

    /**
     * Threads to which an execution can be switched from thread [iThread].
     */
    protected fun switchableThreads(iThread: Int) = (0 until nThreads).filter { it != iThread && isActive(it) }

    // == LISTENING METHODS ==

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    private fun beforeSharedVariableRead(iThread: Int, codeLocation: Int, tracePoint: ReadTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before a shared variable write operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    private fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int, tracePoint: WriteTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    private fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {
        // re-use last call trace point
        newSwitchPoint(iThread, codeLocation, callStackTrace[iThread].lastOrNull()?.call)
    }

    /**
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually acquired
     */
    private fun beforeLockAcquire(codeLocation: Int, tracePoint: MonitorEnterTracePoint?, monitor: Any) {
        val iThread = currentThread
        newSwitchPoint(iThread, codeLocation, tracePoint)
        // Try to acquire the monitor
        if (!monitorTracker.acquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a lock has been found" }
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
            // Now it is possible to acquire the monitor, do it then.
            require(monitorTracker.acquireMonitor(iThread, monitor))
        }
        // The monitor is acquired, finish.
    }

    private fun beforeLockRelease(tracePoint: MonitorExitTracePoint?, monitor: Any) {
        if (suddenInvocationResult != null) return
        monitorTracker.releaseMonitor(monitor)
        traceCollector?.passCodeLocation(tracePoint)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether park should be executed
     */
    private fun beforePark(iThread: Int, codeLocation: Int, tracePoint: ParkTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun afterUnpark(iThread: Int, codeLocation: Int, tracePoint: UnparkTracePoint?, thread: Any) {
        traceCollector?.passCodeLocation(tracePoint)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether `Object.wait` should be executed
     */
    private fun beforeWait(iThread: Int, codeLocation: Int, tracePoint: WaitTracePoint?, monitor: Any, withTimeout: Boolean) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
        failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a waiting on a monitor block has been found" }
        if (withTimeout) return // timeouts occur instantly
        monitorTracker.waitOnMonitor(iThread, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, true)
        require(monitorTracker.acquireMonitor(iThread, monitor)) // acquire the lock again
    }

    private fun beforeNotify(tracePoint: NotifyTracePoint?, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        traceCollector?.passCodeLocation(tracePoint)
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineSuspended(iThread: Int) = runInIgnoredSection {
        check(currentThread == iThread)
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread])) {
            // `COROUTINE_SUSPENSION_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, COROUTINE_SUSPENSION_CODE_LOCATION, null)
        } else {
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, SwitchReason.SUSPENDED, true)
        }
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineResumed() {
        isSuspended[currentThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineCancelled() {
        val iThread = currentThread
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread].clear()
    }

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    private fun beforeMethodCall(iThread: Int, codeLocation: Int, ownerName: String?, methodName: String, params: Array<Any?>) {
        val callStackTrace = callStackTrace[iThread]
        val suspendedMethodStack = suspendedFunctionsStack[iThread]
        val methodId = if (suspendedMethodStack.isNotEmpty()) {
            // if there was a suspension before, then instead of creating a new identifier
            // use the one that the suspended call had
            val lastId = suspendedMethodStack.last()
            suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
            lastId
        } else {
            methodCallNumber++
        }
        // code location of the new method call is currently the last
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread],
            callStackTrace = callStackTrace,
            methodName = methodName,
            stackTraceElement = CodeLocations.stackTrace(codeLocation)
        ).also {
            it.initializeParameters(params)
        }.also {
            it.initializeOwnerName(ownerName)
        }
        methodCallTracePointStack[iThread] += tracePoint
        callStackTrace.add(CallStackTraceElement(tracePoint, methodId))
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    private fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint?) {
        val callStackTrace = callStackTrace[iThread]
        if (tracePoint!!.wasSuspended) {
            // if a method call is suspended, save its identifier to reuse for continuation resuming
            suspendedFunctionsStack[iThread].add(callStackTrace.last().identifier)
        }
        callStackTrace.removeLast()
    }

    // == LOGGING METHODS ==

    /**
     * Creates a new [CoroutineCancellationTracePoint].
     */
    internal fun createAndLogCancellationTracePoint(): CoroutineCancellationTracePoint? {
        if (collectTrace) {
            val cancellationTracePoint = doCreateTracePoint(::CoroutineCancellationTracePoint)
            traceCollector?.passCodeLocation(cancellationTracePoint)
            return cancellationTracePoint
        }
        return null
    }

    private fun <T : TracePoint> doCreateTracePoint(constructor: (iThread: Int, actorId: Int, CallStackTrace) -> T): T {
        val iThread = currentThread
        // use any actor id for non-test threads
        val actorId = currentActorId[iThread]
        return constructor(iThread, actorId, callStackTrace.getOrNull(iThread)?.toList() ?: emptyList())
    }

    // == UTILITY METHODS ==

    override fun lock(monitor: Any, codeLocation: Int): Unit = runInIgnoredSection {
        val tracePoint = if (collectTrace) {
            val iThread = currentThread
            MonitorEnterTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        beforeLockAcquire(codeLocation, tracePoint, monitor)
    }

    override fun unlock(monitor: Any, codeLocation: Int): Unit = runInIgnoredSection {
        val tracePoint = if (collectTrace) {
            val iThread = currentThread
            MonitorExitTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        beforeLockRelease(tracePoint, monitor)
    }

    override fun park(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ParkTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        beforePark(iThread, codeLocation, tracePoint)
    }

    override fun unpark(thread: Thread, codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            UnparkTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        afterUnpark(iThread, codeLocation, tracePoint, thread)
    }

    override fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean): Unit = runInIgnoredSection {
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WaitTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        beforeWait(iThread, codeLocation, tracePoint, monitor, withTimeout)
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean): Unit = runInIgnoredSection {
        val tracePoint = if (collectTrace) {
            val iThread = currentThread
            NotifyTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread], CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        beforeNotify(tracePoint, monitor, notifyAll)
    }


    override fun beforeReadField(obj: Any, className: String, fieldName: String, isFinal: Boolean, codeLocation: Int) {
        if (isFinal) return
        runInIgnoredSection {
            if (localObjectManager.isLocalObject(obj)) return@runInIgnoredSection
            val iThread = currentThread
            val tracePoint = if (collectTrace) {
                ReadTracePoint(
                    iThread, currentActorId[iThread], callStackTrace[iThread],
                    fieldName, CodeLocations.stackTrace(codeLocation)
                )
            } else {
                null
            }
            if (tracePoint != null) {
                lastReadTracePoint.set(tracePoint)
            }
            traceCollector?.addStateRepresentation()
            beforeSharedVariableRead(iThread, codeLocation, tracePoint)
        }
    }

    override fun beforeReadFieldStatic(className: String, fieldName: String, isFinal: Boolean, codeLocation: Int) {
        if (isFinal) return
        runInIgnoredSection {
            val iThread = currentThread
            val tracePoint = if (collectTrace) {
                ReadTracePoint(
                    iThread, currentActorId[iThread], callStackTrace[iThread],
                    fieldName, CodeLocations.stackTrace(codeLocation)
                )
            } else {
                null
            }
            if (tracePoint != null) {
                lastReadTracePoint.set(tracePoint)
            }
            traceCollector?.addStateRepresentation()
            beforeSharedVariableRead(iThread, codeLocation, tracePoint)
        }
    }

    override fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int) = runInIgnoredSection {
        if (localObjectManager.isLocalObject(array)) return@runInIgnoredSection
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread],
                "Array[$index]", CodeLocations.stackTrace(codeLocation))
        } else {
            null
        }
        if (tracePoint != null) {
            lastReadTracePoint.set(tracePoint)
        }
        traceCollector?.addStateRepresentation()
        beforeSharedVariableRead(iThread, codeLocation, tracePoint)
    }

    private var lastReadTracePoint = ThreadLocal<ReadTracePoint?>()

    override fun afterRead(value: Any?) {
        if (collectTrace) {
            runInIgnoredSection {
                lastReadTracePoint.get()?.initializeReadValue(value)
                lastReadTracePoint.set(null)
            }
        }
    }

    override fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int) = runInIgnoredSection {
        localObjectManager.addDependency(obj, value)
        if (localObjectManager.isLocalObject(obj)) {
            return@runInIgnoredSection
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread],
                fieldName, CodeLocations.stackTrace(codeLocation)).also {
                    it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        traceCollector?.addStateRepresentation()
        beforeSharedVariableWrite(iThread, codeLocation, tracePoint)
    }

    override fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int): Unit = runInIgnoredSection {
        localObjectManager.deleteLocalObject(value)
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread],
                fieldName, CodeLocations.stackTrace(codeLocation)).also {
                it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        traceCollector?.addStateRepresentation()
        beforeSharedVariableWrite(iThread, codeLocation, tracePoint)
    }

    override fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int) = runInIgnoredSection {
        localObjectManager.addDependency(array, value)
        if (localObjectManager.isLocalObject(array)) {
            return@runInIgnoredSection
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread],
                "Array[$index]", CodeLocations.stackTrace(codeLocation)).also {
                it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        traceCollector?.addStateRepresentation()
        beforeSharedVariableWrite(iThread, codeLocation, tracePoint)
    }

    private val randoms = (0 until nThreads + 2).map { Random(it.toLong()) }

    override fun getThreadLocalRandom(): Random {
        return randoms[currentThread]
    }

    private val methodCallTracePointStack = (0 until nThreads + 2).map { ArrayList<MethodCallTracePoint>() }

    override fun onMethodCallFinishedSuccessfully(result: Any?) {
        if (collectTrace) {
            runInIgnoredSection {
                val iThread = currentThread
                val tracePoint = methodCallTracePointStack[iThread].removeLast()
                tracePoint.initializeReturnedValue(if (result == Injections.VOID_RESULT) VoidResult else result)
                afterMethodCall(iThread, tracePoint)
            }
        }
    }

    override fun onMethodCallThrewException(t: Throwable) {
        if (collectTrace) {
            runInIgnoredSection {
                // We cannot simply read `thread` as Forcible???Exception can be thrown.
                val iThread =  (Thread.currentThread() as TestThread).threadNumber
                val tracePoint = methodCallTracePointStack[iThread].removeLast()
                tracePoint.initializeThrownException(t)
                afterMethodCall(iThread, tracePoint)
            }
        }
    }

    override fun beforeMethodCall(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        params: Array<Any?>
    ) {
        if (owner is AtomicLongFieldUpdater<*> || owner is AtomicIntegerFieldUpdater<*> || owner is AtomicReferenceFieldUpdater<*, *>) {
            runInIgnoredSection {
                if (collectTrace) {
                    traceCollector?.addStateRepresentation()
                    val ownerName = AtomicFields.getAtomicFieldName(owner)
                    @Suppress("NAME_SHADOWING")
                    val params = params.drop(1).toTypedArray()
                    beforeMethodCall(currentThread, codeLocation, ownerName, methodName, params)
                }
                beforeAtomicMethodCall(currentThread, codeLocation)
            }
        } else if (owner is VarHandle || owner is AtomicReference<*> || owner is AtomicLong || owner is AtomicBoolean
            || owner is AtomicInteger || owner is AtomicIntegerArray || owner is AtomicLongArray || owner is AtomicReferenceArray<*>)
        {
            runInIgnoredSection {
                if (collectTrace) {
                    traceCollector?.addStateRepresentation()
                    beforeMethodCall(currentThread, codeLocation, null, methodName, params)
                }
                beforeAtomicMethodCall(currentThread, codeLocation)
            }
        } else {
            if (collectTrace) {
                runInIgnoredSection {
                    val params = if (isSuspendFunction(className, methodName, params)) params.dropLast(1).toTypedArray() else params
                    beforeMethodCall(currentThread, codeLocation, null, methodName, params)
                }
            }
        }
    }

    private fun isSuspendFunction(className: String, methodName: String, params: Array<Any?>) =
        try {
            getMethod(className.canonicalClassName, methodName, params)?.isSuspendable() ?: false
        } catch (t: Throwable) {
            false
        }

    fun getMethod(className: String, methodName: String, params: Array<Any?>): java.lang.reflect.Method? {
        val clazz = Class.forName(className)

        // Filter methods by name
        val possibleMethods = clazz.declaredMethods.filter { it.name == methodName }

        for (method in possibleMethods) {
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size != params.size) continue

            var match = true
            for (i in parameterTypes.indices) {
                val paramType = params[i]?.javaClass
                if (paramType != null && !parameterTypes[i].isAssignableFrom(paramType)) {
                    match = false
                    break
                }
            }

            if (match) return method
        }

        return null // or throw an exception if a match is mandatory
    }

    override fun beforeMethodCall0(owner: Any?, className: String, methodName: String, codeLocation: Int) {
        beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
    }

    override fun beforeMethodCall1(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        param1: Any?
    ) {
        if (collectTrace) {
            beforeMethodCall(owner, className, methodName, codeLocation, arrayOf(param1))
        } else {
            beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
        }
    }

    override fun beforeMethodCall2(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        param1: Any?,
        param2: Any?
    ) {
        if (collectTrace) {
            beforeMethodCall(owner, className, methodName, codeLocation, arrayOf(param1, param2))
        } else {
            beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
        }
    }

    override fun beforeMethodCall3(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        param1: Any?,
        param2: Any?,
        param3: Any?
    ) {
        if (collectTrace) {
            beforeMethodCall(owner, className, methodName, codeLocation, arrayOf(param1, param2, param3))
        } else {
            beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
        }
    }

    override fun beforeMethodCall4(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        param1: Any?,
        param2: Any?,
        param3: Any?,
        param4: Any?
    ) {
        if (collectTrace) {
            beforeMethodCall(owner, className, methodName, codeLocation, arrayOf(param1, param2, param3, param4))
        } else {
            beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
        }
    }

    override fun beforeMethodCall5(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        param1: Any?,
        param2: Any?,
        param3: Any?,
        param4: Any?,
        param5: Any?
    ) {
        if (collectTrace) {
            beforeMethodCall(owner, className, methodName, codeLocation, arrayOf(param1, param2, param3, param4, param5))
        } else {
            beforeMethodCall(owner, className, methodName, codeLocation, emptyArray())
        }
    }

    private var localObjectManager = LocalObjectManager()

    override fun onNewObjectCreation(obj: Any) {
        if (obj is String || obj is Int || obj is Long || obj is Byte || obj is Char || obj is Float || obj is Double) return
        runInIgnoredSection {
            localObjectManager.newObject(obj)
        }
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class TraceCollector {
        private val _trace = mutableListOf<TracePoint>()
        val trace: List<TracePoint> = _trace

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            _trace += SwitchEventTracePoint(iThread, currentActorId[iThread], reason, callStackTrace[iThread])
        }

        fun finishThread(iThread: Int) {
            _trace += FinishThreadTracePoint(iThread)
        }

        fun passCodeLocation(tracePoint: TracePoint?) {
            // tracePoint can be null here if trace is not available, e.g. in case of suspension
            if (tracePoint != null) _trace += tracePoint
        }

        fun addStateRepresentation() {
            val stateRepresentation = runner.constructStateRepresentation() ?: return
            // use call stack trace of the previous trace point
            val callStackTrace = callStackTrace[currentThread]
            _trace += StateRepresentationTracePoint(currentThread, currentActorId[currentThread], stateRepresentation, callStackTrace)
        }
    }
}

/**
 * This class is a [ParallelThreadsRunner] with some overrides that add callbacks
 * to the strategy so that it can known about some required events.
 */
private class ManagedStrategyRunner(
    private val managedStrategy: ManagedStrategy, testClass: Class<*>, validationFunctions: List<Method>,
    stateRepresentationMethod: Method?, timeoutMs: Long, useClocks: UseClocks
) : ParallelThreadsRunner(managedStrategy, testClass, validationFunctions, stateRepresentationMethod, timeoutMs, useClocks) {
    override fun onStart(iThread: Int) {
        managedStrategy.onStart(iThread)
    }

    override fun onFinish(iThread: Int) {
        managedStrategy.onFinish(iThread)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        managedStrategy.onFailure(iThread, e)
    }

    override fun afterCoroutineSuspended(iThread: Int) {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) {
        managedStrategy.afterCoroutineResumed()
    }

    override fun afterCoroutineCancelled(iThread: Int) {
        managedStrategy.afterCoroutineCancelled()
    }

    override fun constructStateRepresentation(): String? {
        if (stateRepresentationFunction == null) return null
        // Enter ignored section, because Runner will call transformed state representation method
        return runInIgnoredSection {
            super.constructStateRepresentation()
        }
    }

    override fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult {
        // Create a cancellation trace point before `cancel`, so that cancellation trace point
        // precede the events in `onCancellation` handler.
        val cancellationTracePoint = managedStrategy.createAndLogCancellationTracePoint()
        try {
            // Call the `cancel` method.
            val cancellationResult = super.cancelByLincheck(cont, promptCancellation)
            // Pass the result to `cancellationTracePoint`.
            cancellationTracePoint?.initializeCancellationResult(cancellationResult)
            // Invoke `strategy.afterCoroutineCancelled` if the coroutine was cancelled successfully.
            if (cancellationResult != CANCELLATION_FAILED)
                managedStrategy.afterCoroutineCancelled()
            return cancellationResult
        } catch(e: Throwable) {
            cancellationTracePoint?.initializeException(e)
            throw e // throw further
        }
    }
}

/**
 * Detects loops, active locks and live locks when the same code location is visited too often.
 */
private class LoopDetector(private val hangingDetectionThreshold: Int) {
    private var lastIThread = -1 // no last thread
    private val operationCounts = IntIntHashMap()
    var totalOperations = 0

    /**
     * Returns `true` if a loop or a hang is detected,
     * `false` otherwise.
     */
    fun visitCodeLocation(iThread: Int, codeLocation: Int): Boolean {
        // Increase the total number of happened operations for live-lock detection
        totalOperations++
        // Have the thread changed? Reset the counters in this case.
        if (lastIThread != iThread) reset(iThread)
        // Ignore coroutine suspension code locations.
        if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false
        // Increment the number of times the specified code location is visited.
        val count = operationCounts.get(codeLocation).let { if (it == -1) 0 else it } + 1
        operationCounts.put(codeLocation, count)
        // Check whether the count exceeds the maximum number of repetitions for loop/hang detection.
        return count > hangingDetectionThreshold
    }

    /**
     * Resets the counters for the specified thread.
     */
    fun reset(iThread: Int) {
        operationCounts.clear()
        lastIThread = iThread
    }
}

/**
 * Tracks synchronization operations with monitors (acquire/release, wait/notify) to maintain a set of active threads.
 */
private class MonitorTracker(nThreads: Int) {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()
    // Maintains a set of monitors on which each thread is waiting.
    // Note, that a thread can wait on a free monitor if it is waiting for
    // a `notify` call.
    // Stores `null` if thread is not waiting on any monitor.
    private val acquiringMonitors = Array<Any?>(nThreads) { null }
    // Stores `true` for the threads which are waiting for a
    // `notify` call on the monitor stored in `acquiringMonitor`.
    private val waitForNotify = BooleanArray(nThreads) { false }

    /**
     * Performs a logical acquisition.
     */
    fun acquireMonitor(iThread: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val ai = acquiredMonitors.computeIfAbsent(monitor) { MonitorAcquiringInfo(iThread, 0) }
        if (ai.iThread != iThread) {
            acquiringMonitors[iThread] = monitor
            return false
        }
        ai.timesAcquired++
        acquiringMonitors[iThread] = null // re-set
        return true
    }

    /**
     * Performs a logical release.
     */
    fun releaseMonitor(monitor: Any) {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val ai = acquiredMonitors[monitor] ?:
            error("The monitor is not acquired: $monitor")
        ai.timesAcquired--
        if (ai.timesAcquired == 0) acquiredMonitors.remove(monitor)
    }

    /**
     * Returns `true` if the corresponding threads is waiting on some monitor.
     */
    fun isWaiting(iThread: Int): Boolean {
        val monitor = acquiringMonitors[iThread] ?: return false
        return waitForNotify[iThread] || !canAcquireMonitor(iThread, monitor)
    }

    /**
     * Returns `true` if the monitor is already acquired by
     * the thread [iThread], or if this monitor is free to acquire.
     */
    private fun canAcquireMonitor(iThread: Int, monitor: Any) =
        acquiredMonitors[monitor]?.iThread?.equals(iThread) ?: true

    /**
     * Performs a logical wait, [isWaiting] for the specified thread
     * returns `true` until the corresponding [notify] or [notifyAll]
     * is invoked.
     */
    fun waitOnMonitor(iThread: Int, monitor: Any) {
        // TODO: we can add spurious wakeups here
        check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
        releaseMonitor(monitor)
        waitForNotify[iThread] = true
        acquiringMonitors[iThread] = monitor
    }

    /**
     * Just notify all thread. Odd threads will have a spurious wakeup
     */
    fun notify(monitor: Any) = notifyAll(monitor)

    /**
     * Performs the logical `notifyAll`.
     */
    fun notifyAll(monitor: Any): Unit = acquiringMonitors.forEachIndexed { iThread, m ->
        if (monitor === m) waitForNotify[iThread] = false
    }

    /**
     * Stores the number of reentrant acquisitions ([timesAcquired])
     * and the number of thread ([iThread]) that holds the monitor.
     */
    private class MonitorAcquiringInfo(val iThread: Int, var timesAcquired: Int)
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
internal object ForcibleExecutionFinishException : RuntimeException() {
    // do not create a stack trace -- it simply can be unsafe
    override fun fillInStackTrace() = this
}

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known

private const val SPINNING_LOOP_ITERATIONS_BEFORE_YIELD = 100_000
