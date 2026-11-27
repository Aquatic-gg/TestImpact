package gg.aquatic.testimpact.report.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportTasksTest {

    private fun project(realised: AtomicInteger): Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.tasks.register("lazyTest", GradleTest::class.java) { realised.incrementAndGet() }
        project.pluginManager.apply("gg.aquatic.testimpact")
        (project as ProjectInternal).evaluate()
        return project
    }

    @Test
    fun `evaluating a project does not realise its test tasks`() {
        val realised = AtomicInteger()

        project(realised)

        assertEquals(0, realised.get())
    }

    @Test
    fun `configuring the report task realises the test tasks`() {
        val realised = AtomicInteger()
        val project = project(realised)

        project.tasks.getByName(ReportTasks.TASK_NAME)

        assertEquals(true, realised.get() > 0)
    }
}
