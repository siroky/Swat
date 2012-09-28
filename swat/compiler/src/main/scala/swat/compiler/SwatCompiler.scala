package swat.compiler

import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.io.File
import java.io
import tools.nsc.reporters.Reporter
import reflect.internal.util.Position
import collection.mutable

class SwatCompiler(
    val classPath: String,
    val classTarget: String,
    val options: SwatCompilerOptions)
{
    def compile(scalaCode: String): CompilationOutput = {
        val sourceFile = new File(new io.File(java.util.UUID.randomUUID + ".scala"))
        try {
            sourceFile.writeAll(scalaCode)
            compile(sourceFile)
        } finally {
            sourceFile.delete()
        }
    }

    def compile(sourceFile: File): CompilationOutput = {
        val settings = new Settings()
        settings.outdir.value = classTarget
        settings.classpath.value = classPath
        settings.deprecation.value = true
        settings.unchecked.value = true
        settings.feature.value = true

        val reporter = new SilentReporter
        val compiler = new SwatGlobal(settings, reporter)
        val run = new compiler.Run()
        run.compile(List(sourceFile.path))

        if (reporter.errors.nonEmpty) {
            throw new CompilationException(reporter.errors.mkString("\n"))
        }
        CompilationOutput(compiler.swatCompilerPlugin.output, reporter.warnings, reporter.infos)
    }

    private class SwatGlobal(settings: Settings, reporter: Reporter) extends Global(settings, reporter)
    {
        val swatCompilerPlugin = new SwatCompilerPlugin(this)

        override protected def computeInternalPhases() {
            super.computeInternalPhases()
            swatCompilerPlugin.processOptions(options.toList, identity _)
            swatCompilerPlugin.components.foreach(phasesSet += _)
        }
    }

    private class SilentReporter extends Reporter
    {
        val errors = mutable.ListBuffer.empty[String]
        val warnings = mutable.ListBuffer.empty[String]
        val infos = mutable.ListBuffer.empty[String]

        protected def info0(pos: Position, msg: String, severity: this.type#Severity, force: Boolean) {
            val (messages, severityDescription) = severity match {
                case ERROR => (errors, "error")
                case WARNING => (warnings, "warning")
                case INFO => (infos, "info")
            }

            messages += "[%s] %s".format(
                severityDescription,
                try {
                    "Line %s column %s: %s".format(pos.line, pos.column, msg)
                } catch {
                    case _: UnsupportedOperationException => msg
                }
            )
        }
    }
}
