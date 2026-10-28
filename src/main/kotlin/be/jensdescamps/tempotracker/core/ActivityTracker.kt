package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.Rituals
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.InputEvent
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Counts active IDE time. Every [TICK_SECONDS] (and on every branch switch / focus change) the
 * interval since the previous tick is credited to the ticket that was checked out at its start,
 * provided you weren't idle and tracking isn't paused.
 */
@Service(Service.Level.APP)
class ActivityTracker : Disposable {

    private class Attribution(val project: WeakReference<Project>, val ticket: String?)

    @Volatile private var started = false
    @Volatile private var lastInputMs = System.currentTimeMillis()
    @Volatile private var lastTickMs = System.currentTimeMillis()
    @Volatile private var appActive = true
    @Volatile private var lastProject: WeakReference<Project>? = null
    @Volatile private var attribution: Attribution? = null
    @Volatile private var tracking = false
    @Volatile private var future: ScheduledFuture<*>? = null

    @Volatile
    var isPaused = false
        private set

    /** Name of the ritual (meeting) running right now; IDE time isn't counted meanwhile. */
    @Volatile
    var currentRitual: String? = null
        private set

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        IdeEventQueue.getInstance().addPostprocessor(IdeEventQueue.EventDispatcher { event ->
            if (event is InputEvent) lastInputMs = System.currentTimeMillis()
            false
        }, this)
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ flush() }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
    }

    override fun dispose() {
        future?.cancel(false)
    }

    fun pause(paused: Boolean) {
        flush()
        isPaused = paused
        flush()
    }

    fun onApplicationActivation(active: Boolean) {
        flush()
        appActive = active
        if (active) lastInputMs = System.currentTimeMillis()
    }

    /** True while time is being counted for [project] right now. */
    fun isTrackingProject(project: Project): Boolean = tracking && attribution?.project?.get() == project

    /** The project you're working in (focused window, else the last one that had focus). */
    fun activeProject(): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        val focused = open.firstOrNull { WindowManager.getInstance().getFrame(it)?.isActive == true }
        if (focused != null) {
            lastProject = WeakReference(focused)
            return focused
        }
        return lastProject?.get()?.takeIf { it in open } ?: open.singleOrNull()
    }

    fun flush() {
        try {
            tick()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Tempo tracker tick failed", e)
        }
    }

    @Synchronized
    private fun tick() {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastTickMs
        lastTickMs = now
        val settings = TempoSettings.getInstance().state

        val store = TimeStore.getInstance()
        val today = LocalDate.now()
        val ritual = Rituals.activeAt(today, LocalTime.now(), settings.rituals)
            ?.takeUnless { store.isRitualSkipped(today, it.id) }
        currentRitual = ritual?.name

        // 1. Credit the interval that just ended. Skip huge gaps (laptop asleep) and meetings.
        val previous = attribution
        if (tracking && ritual == null && previous != null && elapsedMs in 1..(TICK_SECONDS * 3_000)) {
            val key = previous.ticket ?: if (settings.trackUnassigned) "" else null
            if (key != null) {
                val seconds = (elapsedMs + 500) / 1000
                val start = LocalTime.now().minusSeconds(seconds).format(TIME_FORMAT)
                store.credit(today, key, seconds, start)
                store.ensureRituals(today, settings.rituals) // only on days you actually work
            }
        }

        // 2. Decide what the next interval counts towards.
        val idleMs = settings.idleTimeoutMinutes.coerceAtLeast(1) * 60_000L
        val active = !isPaused && now - lastInputMs <= idleMs && (appActive || !settings.stopOnFocusLoss)
        val project = activeProject()
        val ticket = project?.let { BranchResolver.resolve(it).ticket }
        attribution = project?.let { Attribution(WeakReference(it), ticket) }
        tracking = active && project != null && (ticket != null || settings.trackUnassigned)

        SubmitService.getInstance().onTick(project)
        TimeStoreListener.publish()
    }

    companion object {
        const val TICK_SECONDS = 30L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val LOG = logger<ActivityTracker>()

        fun getInstance(): ActivityTracker = service()
    }
}

class TrackerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { ActivityTracker.getInstance().start() }
    }
}

class TrackerActivationListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        ActivityTracker.getInstance().onApplicationActivation(true)
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        ActivityTracker.getInstance().onApplicationActivation(false)
    }
}
