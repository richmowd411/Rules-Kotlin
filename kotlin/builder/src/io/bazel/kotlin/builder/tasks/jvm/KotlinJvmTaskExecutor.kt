/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.builder.utils.*
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KotlinJvmTaskExecutor @Inject internal constructor(
    private val compiler: KotlinToolchain.KotlincInvoker,
    private val pluginArgsEncoder: KotlinCompilerPluginArgsEncoder,
    private val javaCompiler: JavaCompiler,
    private val jDepsGenerator: JDepsGenerator
) {
    fun execute(context: CompilationTaskContext, task: JvmCompilationTask) {
        // TODO fix error handling
        try {
            val preprocessedTask = task.preprocessingSteps(context)
            context.execute("compile classes") { preprocessedTask.compileAll(context) }
            context.execute("create jar") { preprocessedTask.createOutputJar() }
            context.execute("produce src jar") { preprocessedTask.produceSourceJar() }
            context.execute("generate jdeps") { preprocessedTask.generateJDeps() }
        } catch (ex: Throwable) {
            throw RuntimeException(ex)
        }
    }

    private fun JvmCompilationTask.preprocessingSteps(context: CompilationTaskContext): JvmCompilationTask {
        ensureDirectories(
            directories.temp,
            directories.generatedSources,
            directories.generatedClasses
        )
        val taskWithAdditionalSources = context.execute("expand sources") { expandWithSourceJarSources() }
        return context.execute("kapt") { taskWithAdditionalSources.runAnnotationProcessors(context) }
    }

    private fun JvmCompilationTask.generateJDeps() {
        jDepsGenerator.generateJDeps(this)
    }

    private fun JvmCompilationTask.produceSourceJar() {
        Paths.get(outputs.srcjar).also { sourceJarPath ->
            Files.createFile(sourceJarPath)
            SourceJarCreator(
                sourceJarPath
            ).also { creator ->
                // This check asserts that source jars were unpacked if present.
                check(
                    inputs.sourceJarsList.isEmpty() ||
                            Files.exists(Paths.get(directories.temp).resolve("_srcjars"))
                )
                listOf(
                    // Any (input) source jars should already have been expanded so do not add them here.
                    inputs.javaSourcesList.stream(),
                    inputs.kotlinSourcesList.stream()
                ).stream()
                    .flatMap { it.map { p -> Paths.get(p) } }
                    .also { creator.addSources(it) }
                creator.execute()
            }
        }
    }

    private fun JvmCompilationTask.runAnnotationProcessor(context: CompilationTaskContext): List<String> {
        check(info.plugins.annotationProcessorsList.isNotEmpty()) {
            "method called without annotation processors"
        }
        return getCommonArgs().let { args ->
            args.addAll(pluginArgsEncoder.encode(context, this))
            args.addAll(inputs.kotlinSourcesList)
            args.addAll(inputs.javaSourcesList)
            context.executeCompilerTask(args, false, compiler::compile)
        }
    }

    /**
     * Return a list with the common arguments.
     */
    private fun JvmCompilationTask.getCommonArgs(): MutableList<String> {
        val args = mutableListOf<String>()

        // use -- for flags not meant for the kotlin compiler
        args.addAll(
            "-cp", inputs.joinedClasspath,
            "-api-version", info.toolchainInfo.common.apiVersion,
            "-language-version", info.toolchainInfo.common.languageVersion,
            "-jvm-target", info.toolchainInfo.jvm.jvmTarget,
            // https://github.com/bazelbuild/rules_kotlin/issues/69: remove once jetbrains adds a flag for it.
            "--friend-paths", info.friendPathsList.joinToString(File.pathSeparator)
        )

        args
            .addAll("-module-name", info.moduleName)
            .addAll("-d", directories.classes)

        info.passthroughFlags?.takeIf { it.isNotBlank() }?.also { args.addAll(it.split(" ")) }
        return args
    }

    private fun JvmCompilationTask.runAnnotationProcessors(
        context: CompilationTaskContext
    ): JvmCompilationTask =
        try {
            if (info.plugins.annotationProcessorsList.isEmpty()) {
                this
            } else {
                val kaptOutput = runAnnotationProcessor(context)
                context.whenTracing { printLines("kapt output", kaptOutput) }
                expandWithGeneratedSources()
            }
        } catch (ex: CompilationStatusException) {
            ex.lines.also(context::printCompilerOutput)
            throw ex
        }

    /**
     * Produce the primary output jar.
     */
    private fun JvmCompilationTask.createOutputJar() =
        JarCreator(
            path = Paths.get(outputs.jar),
            normalize = true,
            verbose = false
        ).also {
            it.addDirectory(Paths.get(directories.classes))
            it.addDirectory(Paths.get(directories.generatedClasses))
            it.setJarOwner(info.label, info.bazelRuleKind)
            it.execute()
        }

    private fun JvmCompilationTask.compileAll(context: CompilationTaskContext) {
        ensureDirectories(
            directories.classes
        )
        var kotlinError: CompilationStatusException? = null
        var result: List<String>? = null
        context.execute("kotlinc") {
            result = try {
                compileKotlin(context)
            } catch (ex: CompilationStatusException) {
                kotlinError = ex
                ex.lines
            }
        }
        try {
            context.execute("javac") {
                javaCompiler.compile(this)
            }
        } finally {
            checkNotNull(result).also(context::printCompilerOutput)
            kotlinError?.also { throw it }
        }
    }

    /**
     * Compiles Kotlin sources to classes. Does not compile Java sources.
     */
    fun JvmCompilationTask.compileKotlin(context: CompilationTaskContext): List<String> =
        getCommonArgs().let { args ->
            args.addAll(inputs.javaSourcesList)
            args.addAll(inputs.kotlinSourcesList)
            context.executeCompilerTask(args, false, compiler::compile)
        }

    /**
     * If any srcjars were provided expand the jars sources and create a new [JvmCompilationTask] with the
     * Java and Kotlin sources merged in.
     */
    private fun JvmCompilationTask.expandWithSourceJarSources(): JvmCompilationTask =
        if (inputs.sourceJarsList.isEmpty())
            this
        else expandWithSources(
            SourceJarExtractor(
                destDir = Paths.get(directories.temp).resolve("_srcjars"),
                fileMatcher = IS_JVM_SOURCE_FILE
            ).also {
                it.jarFiles.addAll(inputs.sourceJarsList.map { p -> Paths.get(p) })
                it.execute()
            }.sourcesList.iterator()
        )

    /**
     * Create a new [JvmCompilationTask] with sources found in the generatedSources directory. This should be run after
     * annotation processors have been run.
     */
    private fun JvmCompilationTask.expandWithGeneratedSources(): JvmCompilationTask =
        expandWithSources(
            File(directories.generatedSources).walkTopDown()
                .filter { it.isFile }
                .map { it.path }
                .iterator()
        )

    private fun JvmCompilationTask.expandWithSources(sources: Iterator<String>): JvmCompilationTask =
        updateBuilder { builder ->
            sources.partitionJvmSources(
                { builder.inputsBuilder.addKotlinSources(it) },
                { builder.inputsBuilder.addJavaSources(it) })
        }

    private fun JvmCompilationTask.updateBuilder(
        block: (JvmCompilationTask.Builder) -> Unit
    ): JvmCompilationTask =
        toBuilder().let {
            block(it)
            it.build()
        }
}


