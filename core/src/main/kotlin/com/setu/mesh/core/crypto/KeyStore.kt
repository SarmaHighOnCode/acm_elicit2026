package com.setu.mesh.core.crypto

import com.setu.mesh.core.model.NodeId
import java.security.PublicKey

interface KeyStore {
    fun getPublicKey(originId: NodeId): PublicKey?
}
