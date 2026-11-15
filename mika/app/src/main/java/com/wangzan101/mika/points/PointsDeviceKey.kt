// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.points

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PointsDeviceKey {
    private fun pair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false).build())
            }.generateKeyPair()
        }
        return KeyPair(store.getCertificate(ALIAS).publicKey, store.getKey(ALIAS, null) as PrivateKey)
    }
    fun publicKey(): String = Base64.getEncoder().encodeToString(pair().public.encoded)
    fun sign(input: String): String = PointsSigning.sign(pair().private, input)
    companion object { const val ALIAS = "hivo_points_device" }
}
