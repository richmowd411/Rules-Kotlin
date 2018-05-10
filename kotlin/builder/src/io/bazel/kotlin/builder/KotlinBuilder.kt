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
package io.bazel.kotlin.builder

import com.google.common.collect.ImmutableList
import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.Singleton
import io.bazel.kotlin.builder.mode.jvm.KotlinJvmCompilationExecutor
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.kotlin.model.KotlinModel
import java.nio.file.Files
import java.nio.file.Paths

@Singleton
@Suppress("MemberVisibilityCanBePrivate")
class KotlinBuilder @Inject internal constructor(
    private val commandBuilder: BuildCommandBuilder,
    private val jarToolInvoker: KotlinToolchain.JarToolInvoker,
    private val compilationExector: KotlinJvmCompilationExecutor
) : CommandLineProgram {
    fun execute(command: KotlinModel.BuilderCommand): Int =
        prepareForExecution(command).let { doExecute(it) }

    fun execute(args: List<String>): Int =
        execute(ArgMaps.from(args))

    fun execute(args: ArgMap): Int =
        prepareForExecution(commandBuilder.fromInput(args)).let {
            execute(it)
        }


    private fun doExecute(command: KotlinModel.BuilderCommand): Int {
        return try {
            compilationExector.compile(command)
            0
        } catch (ex: CompilationStatusException) {
            ex.status
        }
    }

    private fun prepareForExecution(originalCommand: KotlinModel.BuilderCommand): KotlinModel.BuilderCommand {
        ensureOutputDirectories(originalCommand)
        return commandBuilder.withSources(originalCommand, unpackSources(originalCommand))
    }

    private fun ensureOutputDirectories(command: KotlinModel.BuilderCommand) {
        Files.createDirectories(Paths.get(command.outputs.classDirectory))
        Files.createDirectories(Paths.get(command.outputs.tempDirectory))
        Files.createDirectories(Paths.get(command.outputs.sourceGenDir))
    }

    private fun unpackSources(command: KotlinModel.BuilderCommand): Iterator<String> {
        if (command.inputs.sourceJarsList.isEmpty()) {
            ImmutableList.of<String>().iterator()
        }

        val sourceUnpackDirectory =
            Paths.get(command.outputs.tempDirectory).let {
                it.resolve("_srcjars").toFile().let {
                    try {
                        it.mkdirs(); it
                    } catch (ex: Exception) {
                        throw RuntimeException("could not create unpack directory at $it", ex)
                    }
                }
            }

        for (sourceJar in command.inputs.sourceJarsList) {
            jarToolInvoker.invoke(listOf("xf", Paths.get(sourceJar).toAbsolutePath().toString()), sourceUnpackDirectory)
        }

        return sourceUnpackDirectory
            .walk()
            .filter { it.name.endsWith(".kt") || it.name.endsWith(".java") }
            .map { it.toString() }
            .iterator()
    }

    override fun apply(args: List<String>): Int {
        return execute(args)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val worker = KotlinToolchain.createInjector(Provider { System.err }).getInstance(BazelWorker::class.java)
            System.exit(worker.apply(args.toList()))
        }
    }
}
