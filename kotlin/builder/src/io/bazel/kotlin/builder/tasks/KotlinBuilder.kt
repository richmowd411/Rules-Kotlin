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
package io.bazel.kotlin.builder.tasks

import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.utils.*
import io.bazel.kotlin.model.KotlinModel
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("MemberVisibilityCanBePrivate")
class KotlinBuilder @Inject internal constructor(
    private val taskBuilder: TaskBuilder,
    private val jvmTaskExecutor: KotlinJvmTaskExecutor
) : CommandLineProgram {
    fun execute(args: List<String>): Int =
        ArgMaps.from(args).let { execute(it) }

    fun execute(args: ArgMap): Int =
        taskBuilder.fromInput(args).let { execute(it) }

    fun execute(command: KotlinModel.CompilationTask): Int {
        ensureDirectories(
            command.directories.classes,
            command.directories.temp,
            command.directories.generatedSources,
            command.directories.generatedClasses
        )
        val updatedCommand = expandWithSourceJarSources(command)
        return try {
            jvmTaskExecutor.compile(updatedCommand)
            0
        } catch (ex: CompilationStatusException) {
            ex.status
        }
    }

    /**
     * If any sourcejars were provided expand the jars sources and create a new [KotlinModel.CompilationTask] with the
     * Java and Kotlin sources merged in.
     */
    private fun expandWithSourceJarSources(command: KotlinModel.CompilationTask): KotlinModel.CompilationTask =
        if (command.inputs.sourceJarsList.isEmpty()) {
            command
        } else {
            SourceJarExtractor(
                destDir = Paths.get(command.directories.temp).resolve("_srcjars"),
                fileMatcher = IS_JVM_SOURCE_FILE
            ).also {
                it.jarFiles.addAll(command.inputs.sourceJarsList.map { Paths.get(it) })
                it.execute()
            }.let {
                command.expandWithSources(it.sourcesList.iterator())
            }
        }

    override fun apply(args: List<String>): Int {
        return execute(args)
    }
}