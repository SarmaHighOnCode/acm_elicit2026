package com.setu.mesh.sim

import kotlin.random.Random

/**
 * Entry point for the SETU mesh simulator.
 *
 * ```
 * ./gradlew :sim:run --args="--nodes 200 --scenario flood --minutes 30 --seed 7"
 * ./gradlew :sim:run --args="--sweep 1 --seed 7"
 * ```
 */
fun main(args: Array<String>) {
    val config = parseArgs(args)

    if (config.sweep != null) {
        when (config.sweep) {
            "1" -> Sweeps.runSweep1(config.seed)
            "2" -> Sweeps.runSweep2(config.seed)
            "3" -> Sweeps.runSweep3(config.seed)
            "4" -> Sweeps.runSweep4(config.seed)
            "all" -> Sweeps.runAll(config.seed)
            else -> error("Unknown sweep: ${config.sweep}. Expected 1, 2, 3, 4, or all.")
        }
        return
    }

    val masterRandom = Random(config.seed)
    val world = Scenario.build(config.scenario, config.nodes, config.range, config.loss, masterRandom)

    val totalTicks = (config.minutes * 60_000L) / World.TICK_MILLIS

    for (t in 0 until totalTicks) {
        world.tick()
    }

    val metrics = Metrics.collect(world.nodes)

    if (config.json) {
        print(metrics.toJsonString())
    } else {
        print(metrics.toHumanString())
    }
}

// ---- CLI argument parsing (hand-rolled, no library) ----

private data class Config(
    val nodes: Int = 200,
    val scenario: String = "flood",
    val minutes: Int = 30,
    val seed: Long = 1,
    val range: Double = 80.0,
    val loss: Double = 0.05,
    val json: Boolean = false,
    val sweep: String? = null,
)

private fun parseArgs(args: Array<String>): Config {
    var nodes = 200
    var scenario = "flood"
    var minutes = 30
    var seed = 1L
    var range = 80.0
    var loss = 0.05
    var json = false
    var sweep: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--nodes" -> { nodes = args[++i].toInt() }
            "--scenario" -> { scenario = args[++i] }
            "--minutes" -> { minutes = args[++i].toInt() }
            "--seed" -> { seed = args[++i].toLong() }
            "--range" -> { range = args[++i].toDouble() }
            "--loss" -> { loss = args[++i].toDouble() }
            "--json" -> { json = true }
            "--sweep" -> { sweep = args[++i] }
            "--help", "-h" -> {
                println("""
                    SETU Mesh Simulator
                    
                    Usage: --nodes N --scenario S --minutes M --seed X [--range R] [--loss L] [--json] [--sweep 1|2|3|4|all]
                    
                    Options:
                      --nodes N       Number of nodes (default: 200)
                      --scenario S    Scenario name: ${Scenario.NAMES.joinToString()} (default: flood)
                      --minutes M     Virtual minutes to simulate (default: 30)
                      --seed X        Random seed for determinism (default: 1)
                      --range R       Radio range in metres (default: 80)
                      --loss L        Flat packet loss rate 0..1 (default: 0.05)
                      --json          Emit JSON instead of human-readable table
                      --sweep N       Run sweep benchmark 1..4 or 'all'
                """.trimIndent())
                return Config()
            }
            else -> error("Unknown argument: ${args[i]}. Use --help for usage.")
        }
        i++
    }

    return Config(nodes, scenario, minutes, seed, range, loss, json, sweep)
}
