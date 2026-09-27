package dev.alessiodam.ccstudio.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

abstract class BuildExtension extends DefaultTask {
    @Inject
    abstract ExecOperations getExecOperations()

    @Internal
    abstract DirectoryProperty getExtensionDir()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSources()

    @Input
    abstract Property<String> getPackageManager()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @TaskAction
    void build() {
        def directory = extensionDir.get().asFile
        def output = outputDir.get().asFile.absolutePath
        def preferred = packageManager.get()
        def bun = preferred == 'npm' ? null : findExecutable('bun')
        if (bun != null) {
            logger.lifecycle('Building the editor extension with bun')
            run(directory, [bun, 'install', '--frozen-lockfile'])
            run(directory, [bun, 'run', './build.mjs', "--out=${output}".toString()])
            return
        }
        if (preferred == 'bun') throw new GradleException('bun was requested but could not be found')

        def npm = findExecutable('npm')
        def node = findExecutable('node')
        if (npm == null || node == null) throw new GradleException('Building the editor extension needs bun or Node.js with npm on the PATH')
        logger.lifecycle('Building the editor extension with npm')
        run(directory, [npm, new File(directory, 'package-lock.json').exists() ? 'ci' : 'install'])
        run(directory, [node, './build.mjs', "--out=${output}".toString()])
    }

    void run(File directory, List<String> command) {
        execOperations.exec {
            workingDir = directory
            commandLine = command
        }
    }

    static String findExecutable(String name) {
        def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        def names = windows ? ["${name}.exe", "${name}.cmd", name] : [name]
        def directories = (System.getenv('PATH') ?: '').split(File.pathSeparator).toList()
        directories.add(new File(System.getProperty('user.home'), ".${name}/bin").absolutePath)
        for (directory in directories) {
            for (candidate in names) {
                def file = new File(directory, candidate.toString())
                if (file.isFile() && file.canExecute()) return file.absolutePath
            }
        }
        return null
    }
}
