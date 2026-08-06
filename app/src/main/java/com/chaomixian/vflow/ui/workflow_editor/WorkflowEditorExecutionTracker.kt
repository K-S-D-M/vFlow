package com.chaomixian.vflow.ui.workflow_editor

import androidx.lifecycle.LifecycleCoroutineScope
import com.chaomixian.vflow.core.execution.ExecutionState
import com.chaomixian.vflow.core.workflow.model.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class WorkflowEditorExecutionTracker(
    private val isWorkflowRunning: (String) -> Boolean,
    private val stopWorkflowExecution: (String) -> Unit,
    private val updateExecuteButton: (Boolean) -> Unit,
    private val highlightStep: (Int) -> Unit,
    private val highlightStepAsFailed: (Int) -> Unit,
    private val clearHighlight: () -> Unit,
    private val onLogUpdated: ((CharSequence) -> Unit)? = null,
    private val onExecutionFinished: ((ExecutionState) -> Unit)? = null,
) {
    private var currentlyExecutingWorkflowId: String? = null
    private var currentlyExecutingExecutionInstanceId: String? = null
    private val logBuffer = StringBuilder()

    fun observe(scope: LifecycleCoroutineScope, stateFlow: Flow<ExecutionState>) {
        scope.launch {
            stateFlow.collectLatest { state ->
                if (!shouldHandleExecutionState(state)) return@collectLatest

                when (state) {
                    is ExecutionState.Running -> handleRunningExecutionState(state)
                    is ExecutionState.Finished, is ExecutionState.Cancelled -> {
                        appendLogFromState(state)
                        clearExecutionTracking()
                        updateExecuteButton(false)
                        clearHighlight()
                        onExecutionFinished?.invoke(state)
                    }
                    is ExecutionState.Failure -> {
                        appendLogFromState(state)
                        clearExecutionTracking()
                        updateExecuteButton(false)
                        highlightStepAsFailed(state.stepIndex)
                        onExecutionFinished?.invoke(state)
                    }
                }
            }
        }
    }

    /** 追加一条外部日志（例如执行前提示） */
    fun appendLog(message: CharSequence) {
        if (message.isEmpty()) return
        if (logBuffer.isNotEmpty() && !logBuffer.endsWith('\n')) logBuffer.append('\n')
        logBuffer.append(message)
        onLogUpdated?.invoke(logBuffer)
    }

    fun clearLogs() {
        logBuffer.clear()
        onLogUpdated?.invoke("")
    }

    fun getCurrentLog(): CharSequence = logBuffer

    private fun appendLogFromState(state: ExecutionState) {
        val detailed = when (state) {
            is ExecutionState.Finished -> state.detailedLog
            is ExecutionState.Cancelled -> state.detailedLog
            is ExecutionState.Failure -> state.detailedLog
            else -> null
        }
        if (!detailed.isNullOrEmpty()) {
            if (logBuffer.isNotEmpty() && !logBuffer.endsWith('\n')) logBuffer.append('\n')
            logBuffer.append(detailed)
            onLogUpdated?.invoke(logBuffer)
        }
    }

    fun syncExecutionUiForWorkflow(workflow: Workflow?, preserveRunningInstance: Boolean = false) {
        val workflowId = workflow?.id
        val isRunning = workflowId?.let(isWorkflowRunning) == true
        updateExecuteButton(isRunning)
        if (isRunning) {
            if (!(preserveRunningInstance && currentlyExecutingWorkflowId == workflowId)) {
                beginExecutionTracking(requireNotNull(workflowId))
            }
        } else {
            clearExecutionTracking()
        }
    }

    fun beginExecutionTracking(workflowId: String) {
        currentlyExecutingWorkflowId = workflowId
        currentlyExecutingExecutionInstanceId = null
        logBuffer.clear()
        onLogUpdated?.invoke("")
    }

    fun finishExecutionLaunch(executionInstanceId: String) {
        if (executionInstanceId.isBlank()) {
            clearExecutionTracking()
        } else {
            currentlyExecutingExecutionInstanceId = executionInstanceId
        }
    }

    fun isCurrentWorkflowExecuting(): Boolean {
        val workflowId = currentlyExecutingWorkflowId ?: return false
        return isWorkflowRunning(workflowId)
    }

    fun stopTrackedWorkflowExecution() {
        currentlyExecutingWorkflowId?.let(stopWorkflowExecution)
    }

    private fun shouldHandleExecutionState(state: ExecutionState): Boolean {
        val executingId = currentlyExecutingWorkflowId ?: return false
        if (state.workflowId != executingId) return false
        val executingInstanceId = currentlyExecutingExecutionInstanceId
        return executingInstanceId == null || state.executionInstanceId == executingInstanceId
    }

    private fun handleRunningExecutionState(state: ExecutionState.Running) {
        if (currentlyExecutingExecutionInstanceId == null) {
            currentlyExecutingExecutionInstanceId = state.executionInstanceId
        }
        updateExecuteButton(true)
        highlightStep(state.stepIndex)
    }

    private fun clearExecutionTracking() {
        currentlyExecutingWorkflowId = null
        currentlyExecutingExecutionInstanceId = null
    }
}
