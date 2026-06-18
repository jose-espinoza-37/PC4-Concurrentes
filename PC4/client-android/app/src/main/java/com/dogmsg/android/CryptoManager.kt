package com.dogmsg.android

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * T-B7 / Contrato de Encriptacion (espejo de CryptoManager.java).
 *
 * Esquema E2E usando SOLO javax.crypto (disponible en Android, sin dependencias
 * externas):
 *   1. Diffie-Hellman para acordar un secreto compartido entre dos usuarios.
 *   2. El secreto pasa por SHA-256 para derivar una clave AES de 256 bits.
 *   3. Cada mensaje se cifra con AES-256-CBC e IV aleatorio. El IV (16 bytes)
 *      se antepone al ciphertext en el payload.
 *
 * Para que Java, Kotlin y Python deriven la MISMA clave deben usar los mismos
 * parametros DH (aqui los grupos estandar del JDK/Android, clave de 2048 bits).
 */
class CryptoManager {

    private val random = SecureRandom()
    private var keyPair: KeyPair? = null
    private var aesKey: SecretKeySpec? = null

    /**
     * Genera un par DH y devuelve la clave PUBLICA codificada
     * (X.509 / SubjectPublicKeyInfo) para enviarla al otro usuario.
     *
     * FIX CRITICO: antes se usaba kpg.initialize(2048, random), que deja que
     * CADA proveedor (JDK en desktop, BoringSSL/Conscrypt en Android) elija
     * su PROPIO grupo DH aleatorio (primo p y generador g). Esto hacia que
     * Java y Android terminaran derivando claves AES distintas sin avisar
     * (o directamente fallaran al parsear la clave del otro lado), causando
     * "mensaje cifrado: estableciendo clave..." indefinido o excepciones en
     * KEY_EXCHANGE. Ahora se usa MODP_2048 (RFC 3526, grupo 14), un grupo DH
     * estandar y publico, FIJO e IDENTICO en Java/Kotlin/Python -- todas las
     * partes deben usar el mismo p/g para que el acuerdo de claves funcione.
     */
    fun generateKeyPair(): ByteArray {
        val kpg = KeyPairGenerator.getInstance(DH)
        kpg.initialize(DhGroups.MODP_2048, random)
        val kp = kpg.generateKeyPair()
        keyPair = kp
        return kp.public.encoded
    }

    /** La clave publica propia ya codificada (para reenviar). */
    fun getPublicKeyEncoded(): ByteArray {
        val kp = keyPair ?: throw IllegalStateException("Llama a generateKeyPair() primero")
        return kp.public.encoded
    }

    /** Igual que [getPublicKeyEncoded] pero devuelve null si no hay par aun. */
    fun getPublicKeyEncodedOrNull(): ByteArray? = keyPair?.public?.encoded

    /**
     * Deriva la clave AES-256 compartida a partir de la clave publica del otro
     * usuario (recibida via KEY_EXCHANGE).
     */
    fun deriveSharedKey(peerPublicKeyEncoded: ByteArray) {
        val kp = keyPair ?: throw IllegalStateException("Llama a generateKeyPair() primero")

        val kf = KeyFactory.getInstance(DH)
        val peerKey = kf.generatePublic(X509EncodedKeySpec(peerPublicKeyEncoded))

        val ka = KeyAgreement.getInstance(DH)
        ka.init(kp.private)
        ka.doPhase(peerKey, true)
        val sharedSecret = ka.generateSecret()

        val sha = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha.digest(sharedSecret) // 32 bytes = AES-256
        aesKey = SecretKeySpec(keyBytes, "AES")
    }

    fun isReady(): Boolean = aesKey != null

    /** Cifra con AES-256-CBC. Devuelve IV(16) || ciphertext. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = requireKey()
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_CBC)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext)

        val out = ByteArray(IV_LEN + ct.size)
        System.arraycopy(iv, 0, out, 0, IV_LEN)
        System.arraycopy(ct, 0, out, IV_LEN, ct.size)
        return out
    }

    /** Descifra un payload con formato IV(16) || ciphertext. */
    fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        val key = requireKey()
        require(ivAndCiphertext.size >= IV_LEN) { "Payload cifrado demasiado corto" }
        val iv = ivAndCiphertext.copyOfRange(0, IV_LEN)
        val ct = ivAndCiphertext.copyOfRange(IV_LEN, ivAndCiphertext.size)

        val cipher = Cipher.getInstance(AES_CBC)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun requireKey(): SecretKeySpec =
        aesKey ?: throw IllegalStateException("Clave compartida no derivada todavia")

    companion object {
        private const val DH = "DH"
        private const val AES_CBC = "AES/CBC/PKCS5Padding"
        private const val IV_LEN = 16

        /** SHA-256(password) en hex. El servidor aplica su propio salt por usuario. */
        fun hashPassword(password: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val d = sha.digest(password.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder()
            for (b in d) sb.append("%02x".format(b))
            return sb.toString()
        }
    }
}

/**
 * Grupo Diffie-Hellman FIJO (RFC 3526, 2048-bit MODP Group 14) compartido por
 * TODOS los clientes (Java, Kotlin, y eventualmente Python). El primo p y el
 * generador g deben ser EXACTAMENTE los mismos en cada implementacion del
 * protocolo, o el acuerdo de claves entre plataformas distintas falla o
 * deriva secretos diferentes sin aviso. Ver CryptoManager.generateKeyPair().
 *
 * El valor de P_HEX fue calculado programaticamente a partir de la formula
 * exacta de la RFC 3526 seccion 3 (p = 2^2048 - 2^1984 - 1 + 2^64 * { [2^1918
 * * pi] + 124476 }) usando aritmetica de precision arbitraria, NO transcrito
 * a mano desde el texto del RFC -- evita errores de copiado en un numero de
 * 512 caracteres hexadecimales. Verificado: 2048 bits exactos, coincide con
 * el fragmento inicial publicado (FFFFFFFFFFFFFFFFC90FDAA22168C234...).
 */
object DhGroups {
    private const val P_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC7" +
                "4020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14" +
                "374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B" +
                "7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163" +
                "BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208" +
                "552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E" +
                "36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69" +
                "55817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFF" +
                "FFFFFFFFF"

    val MODP_2048: DHParameterSpec = DHParameterSpec(
        java.math.BigInteger(P_HEX, 16),
        java.math.BigInteger.valueOf(2) // generador g = 2 (RFC 3526)
    )
}