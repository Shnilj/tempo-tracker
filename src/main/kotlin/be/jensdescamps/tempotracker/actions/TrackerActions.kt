package be.jensdescamps.tempotracker.actions

import be.jensdescamps.tempotracker.core.ActivityTracker
import be.jensdescamps.tempotracker.core.BranchResolver
import be.jensdescamps.tempotracker.core.TimeStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.Messages

class TogglePauseAction : DumbAwareToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean = ActivityTracker.getInstance().isPaused

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ActivityTracker.getInstance().pause(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = AllIcons.Actions.Pause
        e.presentation.text = if (ActivityTracker.getInstance().isPaused) "Resume Time Tracking" else "Pause Time Tracking"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** For branches like `develop` or `fix/login` that don't contain a ticket key. */
class AssignTicketAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val info = BranchResolver.resolve(project)
        val branch = info.branch
        if (branch == null) {
            Messages.showInfoMessage(project, "No Git branch is checked out in this project.", "Tempo Tracker")
            return
        }
        val input = Messages.showInputDialog(
            project,
            "Jira ticket for branch \"$branch\" (empty to remove the assignment):",
            "Assign Jira Ticket",
            null,
            info.ticket.orEmpty(),
            null,
        ) ?: return
        ActivityTracker.getInstance().flush() // credit time so far to the old ticket
        TimeStore.getInstance().assignTicket(branch, input.trim().uppercase().ifBlank { null })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
