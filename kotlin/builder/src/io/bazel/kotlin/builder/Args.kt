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

import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi

typealias ArgMap = Map<String, List<String>>

/**
 * Get the mandatory single value from a key
 */
fun ArgMap.mandatorySingle(key: String): String =
        optionalSingle(key) ?: throw IllegalArgumentException("$key is not optional")

fun ArgMap.optionalSingle(key: String): String? =
        optional(key)?.let {
            when (it.size) {
                0 -> throw IllegalArgumentException("$key did not have a value")
                1 -> it[0]
                else -> throw IllegalArgumentException("$key should have a single value")
            }
        }

fun ArgMap.mandatory(key: String): Array<String> = optional(key) ?: throw IllegalArgumentException("$key is not optional")
fun ArgMap.optional(key: String): Array<String>? = this[key]?.toTypedArray()

inline fun <reified T : Any> ArgMap.mandatoryFromJson(key: String): T = optionalFromJson(key) ?: throw IllegalArgumentException("$key is not optional")
inline fun <reified T : Any> ArgMap.optionalFromJson(key: String): T? = optionalSingle(key)?.let { moshi.adapter(T::class.java).fromJson(it)!! }

@PublishedApi
internal val moshi = Moshi.Builder().let {
    it.add(KotlinJsonAdapterFactory())
    it.build()
}

/**
 * Test if a flag is set
 */
fun ArgMap.flag(key: String): Boolean = this[key]?.let { true } ?: false

object ArgMaps {
    fun from(args: List<String>): ArgMap = mutableMapOf<String, MutableList<String>>().also { argsToMap(args, it) }

    fun argsToMap(args: List<String>, argMap: MutableMap<String, MutableList<String>>,  isFlag: (String) -> Boolean = { it.startsWith("--") }) {
        var currentKey: String = args.first().also { require(isFlag(it)) { "first arg must be a flag" } }
        val currentValue = mutableListOf<String>()
        val mergeCurrent = {
            argMap.computeIfAbsent(currentKey, { mutableListOf() }).addAll(currentValue)
            currentValue.clear()
        }
        args.drop(1).forEach {
            if (it.startsWith("--")) {
                mergeCurrent()
                currentKey = it
            } else {
                currentValue.add(it)
            }
        }.also { mergeCurrent() }
    }
}