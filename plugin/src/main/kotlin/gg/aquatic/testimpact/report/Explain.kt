package gg.aquatic.testimpact.report

/** Answers `--explain <TestClass>` from a finished report. */
internal object Explain {

    private const val MAX_NAME_LENGTH = 512
    private const val PART = "[\\p{L}_$][\\p{L}\\p{N}_$]*"
    private val BINARY_NAME = Regex("$PART(\\.$PART)*")

    /** Returns [name] when it is a Java binary class name; anything else is refused naming the option. */
    fun validate(name: String): String {
        require(name.length in 1..MAX_NAME_LENGTH && BINARY_NAME.matches(name)) {
            "--explain expects a class name such as com.example.FooTest (1 to $MAX_NAME_LENGTH characters)"
        }
        return name
    }

    /** One line per Test task of the project, preceded by a header. A nested class is explained by its top-level class. */
    fun explain(report: ImpactReport, name: String): List<String> {
        val top = topLevel(validate(name))
        val lines = ArrayList<String>()
        lines.add(
            if (top == name) "explain $top in ${report.project}"
            else "explain $name in ${report.project}: a nested class is selected through its top-level class $top",
        )
        if (report.tasks.isEmpty()) {
            lines.add("${report.project} has no Test tasks")
            return lines
        }
        for (task in report.tasks) {
            val test = task.tests.firstOrNull { it.className == top }
            lines.add(
                when {
                    task.result == ImpactReport.Result.FULL ->
                        "${task.path}: full run, so $top runs: ${task.rule?.let(ReportText::describe).orEmpty()}"

                    test != null -> "${task.path}: selected: ${ReportText.testLine(test)}"
                    else ->
                        "${task.path}: not selected: no changed file reaches it, or it is not a test class of ${task.path}"
                },
            )
        }
        return lines
    }

    private fun topLevel(name: String): String {
        val nested = name.indexOf('$', 1)
        return if (nested > 0) name.substring(0, nested) else name
    }
}
