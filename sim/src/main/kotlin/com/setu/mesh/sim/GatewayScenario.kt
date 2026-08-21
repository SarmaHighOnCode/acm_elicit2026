package com.setu.mesh.sim

/**
 * Returns the gateway node for the scenario. 
 * This assumes that there is exactly one gateway node.
 */
fun World.gatewayNode(): SimNode = nodes.first { it.isGateway }
