package be.jensdescamps.tempotracker.ui

import be.jensdescamps.tempotracker.TOOL_WINDOW_ID
import be.jensdescamps.tempotracker.core.ActivityTracker
import be.jensdescamps.tempotracker.core.BranchResolver
import be.jensdescamps.tempotracker.core.Durations
import be.jensdescamps.tempotracker.core.TimeStore
import be.jensdescamps.tempotracker.core.TimeStoreListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.time.LocalDate

class TrackerStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = TrackerStatusWidget.ID

    override fun getDisplayName() = "Tempo Tracker"

    override fun createWidget(project: Project): StatusBarWidget = TrackerStatusWidget(project)
}

/** Shows e.g. "ART01DEV10-4466 · 1h 05m" in the status bar; click opens the tool window. */
class TrackerStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(TimeStoreListener.TOPIC, TimeStoreListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) this.statusBar?.updateWidget(ID)
                }
            })
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getText(): String {
        val info = BranchResolver.resolve(project)
        val ticket = info.ticket ?: return if (info.branch == null) "" else "Tempo: no ticket"
        val tracker = ActivityTracker.getInstance()
        val today = TimeStore.getInstance().entries(LocalDate.now())
            .firstOrNull { it.ticket == ticket }?.trackedSeconds ?: 0L
        val suffix = when {
            tracker.isPaused -> " (paused)"
            tracker.currentRitual != null -> " (${tracker.currentRitual})"
            tracker.isTrackingProject(project) -> ""
            else -> " (idle)"
        }
        return "$ticket · ${Durations.format(today)}$suffix"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "Tempo Tracker: time tracked today on this ticket. Click to review."

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer<MouseEvent> {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    companion object {
        const val ID = "TempoTrackerStatus"
    }
}
