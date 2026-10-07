package com.civicwalk.cli

import com.civicwalk.core.TextTruncate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

/**
 * CLI harness — runs the SAME Gemma model file (.litertlm) that the Android app will bundle,
 * so the prompt, the latency and the output can be verified without a phone.
 *
 * Usage: gradle :cli:run --args="<model.litertlm>"
 */
private const val PROMPT = """You are the on-device writer of a Hong Kong walking notebook.
The user photographed something they passed while walking.
Place: a street corner in Sham Shui Po, Hong Kong.
Local time: 2026-10-07 18:20.

Write a short field note: what can be seen, and one thing worth looking up later.
Rules: Traditional Chinese; 60-140 characters; do not invent street names or districts;
no greeting, no heading; output only the note itself."""

fun main(args: Array<String>) = runBlocking {
    val modelPath = args.firstOrNull() ?: System.getenv("WALKNOTES_MODEL")
        ?: run { println("usage: :cli:run --args=\"/path/model.litertlm\""); return@runBlocking }

    println("model: $modelPath")
    val engineConfig = EngineConfig(modelPath = modelPath, backend = Backend.CPU())
    val engine = Engine(engineConfig)

    val loadMs = measureTimeMillis { engine.initialize() }
    println("engine.initialize(): ${loadMs} ms")

    var outLen = 0
    val genMs = measureTimeMillis {
        engine.createConversation().use { conversation ->
            val sb = StringBuilder()
            conversation.sendMessageAsync(PROMPT).collect { chunk ->
                sb.append(chunk); print(chunk); System.out.flush()
            }
            outLen = sb.length
            println()
            println("--- raw length: $outLen chars ---")
            val utf8 = sb.toString().toByteArray(Charsets.UTF_8).size
            val cut = TextTruncate.truncateUtf8(sb.toString(), 600)
            println("--- utf8 bytes: $utf8 | after TextTruncate(600): ${cut.toByteArray(Charsets.UTF_8).size} bytes ---")
        }
    }
    println("generation: ${genMs} ms for $outLen chars (${if (genMs > 0) outLen * 1000.0 / genMs else 0.0} chars/s)")
    engine.close()
}
