package com.assistant.services.actions

sealed class ActionResult {
    data object Success : ActionResult()
    data class SuccessWithFeedback(val feedback: String) : ActionResult()
    data object Failure : ActionResult()
    data class AskUser(val question: String) : ActionResult()
    data class EndSession(val acknowledgement: String) : ActionResult() // User wants to quit
}
