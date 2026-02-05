@file:JvmName("RunAllTests")

package org.springframework.samples.petclinic.imperative

import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import kotlin.system.exitProcess

private const val TEST_PACKAGE = "org.springframework.samples.petclinic.imperative"

fun main() {
    val summaryListener = SummaryGeneratingListener()
    val launcher = LauncherFactory.create()
    val request = LauncherDiscoveryRequestBuilder.request()
        .selectors(selectPackage(TEST_PACKAGE))
        .build()

    launcher.registerTestExecutionListeners(summaryListener, ConsoleProgressListener())
    launcher.execute(request)

    val summary = summaryListener.summary
    println()
    println(
        "Test run finished: " +
            "${summary.testsSucceededCount} succeeded, " +
            "${summary.testsFailedCount} failed, " +
            "${summary.testsAbortedCount} aborted, " +
            "${summary.testsSkippedCount} skipped"
    )

    if (summary.totalFailureCount > 0) {
        summary.printFailuresTo(PrintWriter(System.err, true))
        exitProcess(1)
    }
}

private class ConsoleProgressListener : TestExecutionListener {
    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        println("Running ${testPlan.countTestIdentifiers(TestIdentifier::isTest)} tests")
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (testIdentifier.isTest) {
            println("START ${testIdentifier.displayName}")
        }
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: org.junit.platform.engine.TestExecutionResult
    ) {
        if (testIdentifier.isTest) {
            println("${testExecutionResult.status} ${testIdentifier.displayName}")
        }
    }
}
