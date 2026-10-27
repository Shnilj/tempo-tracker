package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

data class BranchInfo(val branch: String?, val ticket: String?)

object BranchResolver {
    /**
     * The ticket the project is currently on. With several Git roots, the first repository whose
     * branch maps to a ticket wins. A manual branch assignment beats the branch-name pattern.
     */
    fun resolve(project: Project): BranchInfo {
        if (project.isDisposed) return BranchInfo(null, null)
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) return BranchInfo(null, null)
        val settings = TempoSettings.getInstance()
        val store = TimeStore.getInstance()
        for (repository in repositories) {
            val branch = repository.currentBranchName ?: continue
            val ticket = store.assignedTicket(branch)
                ?: TicketParser.parse(branch, settings.state.branchPattern, settings.projectKeyList)
            if (ticket != null) return BranchInfo(branch, ticket)
        }
        return BranchInfo(repositories.firstNotNullOfOrNull { it.currentBranchName }, null)
    }
}

/** Credits time to the old branch the moment you switch, instead of at the next tick. */
class BranchChangeListener : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        ActivityTracker.getInstance().flush()
    }
}
