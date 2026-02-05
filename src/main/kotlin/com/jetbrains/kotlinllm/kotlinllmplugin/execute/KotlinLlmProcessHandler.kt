package com.jetbrains.kotlinllm.kotlinllmplugin.execute

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.sun.jdi.VirtualMachine
import java.io.OutputStream

class KotlinLlmProcessHandler(
    private val vm: VirtualMachine
) : ProcessHandler() {
    @Volatile
    private var isTerminating = false

    private val ansiDecoder = AnsiEscapeDecoder()

    override fun startNotify() {
        super.startNotify()

        // Redirect VM output streams to console
        val outputThread = Thread {
            vm.process()?.inputStream?.use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    if (!isProcessTerminating && !isProcessTerminated) {
                        val text = String(buffer, 0, len)
                        ansiDecoder.escapeText(text, ProcessOutputTypes.STDOUT) { chunk, outputType ->
                            notifyTextAvailable(chunk, outputType)
                        }
                    }
                }
            }
        }
        outputThread.isDaemon = true
        outputThread.name = "KotlinLLM-Output-Reader"
        outputThread.start()

        val errorThread = Thread {
            vm.process()?.errorStream?.use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    if (!isProcessTerminating && !isProcessTerminated) {
                        val text = String(buffer, 0, len)
                        ansiDecoder.escapeText(text, ProcessOutputTypes.STDERR) { chunk, outputType ->
                            notifyTextAvailable(chunk, outputType)
                        }
                    }
                }
            }
        }
        errorThread.isDaemon = true
        errorThread.name = "KotlinLLM-Error-Reader"
        errorThread.start()
    }

    override fun destroyProcessImpl() {
        isTerminating = true
        notifyTextAvailable("\nProcess terminated by user\n", ProcessOutputTypes.SYSTEM)
        try {
            vm.exit(0)
        } catch (_: Exception) {
            // VM might already be terminated
        }
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        isTerminating = true
        notifyTextAvailable("\nProcess detached\n", ProcessOutputTypes.SYSTEM)
        try {
            vm.dispose()
        } catch (_: Exception) {
            // VM might already be terminated
        }
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = vm.process()?.outputStream

    fun notifyTerminated(exitCode: Int) {
        if (!isTerminating && !isProcessTerminated) {
            isTerminating = true
            if (exitCode == 0) {
                notifyTextAvailable("\nProcess finished with exit code 0\n", ProcessOutputTypes.SYSTEM)
            }
            notifyProcessTerminated(exitCode)
        }
    }
}
