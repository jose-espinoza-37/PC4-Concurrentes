package dogmsg.client;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * T-B7 / Contrato de Encriptacion.
 *
 * <p>Implementa el esquema E2E del proyecto usando SOLO {@code javax.crypto}
 * (sin dependencias externas):</p>
 * <ol>
 *   <li>Diffie-Hellman para acordar un secreto compartido entre dos usuarios.</li>
 *   <li>El secreto se pasa por SHA-256 para derivar una clave AES de 256 bits.</li>
 *   <li>Cada mensaje se cifra con AES-256-CBC e IV aleatorio. El IV (16 bytes)
 *       se antepone al ciphertext en el payload.</li>
 * </ol>
 *
 * <p>Uso tipico por conversacion:</p>
 * <pre>
 *   CryptoManager c = new CryptoManager();
 *   byte[] myPub = c.generateKeyPair();         // enviar via KEY_EXCHANGE
 *   c.deriveSharedKey(peerPublicKeyBytes);      // al recibir la del otro
 *   byte[] ct = c.encrypt("hola".getBytes());   // FLAG_ENCRYPTED = 1
 *   byte[] pt = c.decrypt(ct);
 * </pre>
 *
 * <p>Para que Kotlin y Python deriven la MISMA clave, los tres deben usar los
 * mismos parametros DH. Aqui se usan los grupos estandar del JDK (clave de
 * 2048 bits). Si el equipo prefiere parametros fijos, fijar un primo/base
 * comun en los tres lenguajes.</p>
 */
public class CryptoManager {

    private static final String DH = "DH";
    private static final String AES_CBC = "AES/CBC/PKCS5Padding";
    private static final int IV_LEN = 16;

    private final SecureRandom random = new SecureRandom();
    private KeyPair keyPair;
    private SecretKeySpec aesKey;

    /**
     * Genera un par de claves DH y devuelve la clave PUBLICA codificada
     * (X.509 / SubjectPublicKeyInfo) para enviarla al otro usuario.
     *
     * FIX CRITICO: antes se usaba kpg.initialize(2048, random), que deja que
     * CADA proveedor (JDK aqui, BoringSSL/Conscrypt en Android) elija su
     * PROPIO grupo DH aleatorio (primo p y generador g). Esto hacia que
     * Java y Android derivaran claves AES distintas sin avisar (o fallaran
     * al parsear la clave del otro lado). Ahora se usa MODP_2048 (RFC 3526,
     * grupo 14), fijo e identico en Java/Kotlin/Python.
     */
    public byte[] generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(DH);
        kpg.initialize(DhGroups.MODP_2048, random);
        this.keyPair = kpg.generateKeyPair();
        return keyPair.getPublic().getEncoded();
    }

    /** La clave publica propia ya codificada (para reenviar). */
    public byte[] getPublicKeyEncoded() {
        if (keyPair == null) throw new IllegalStateException("Llama a generateKeyPair() primero");
        return keyPair.getPublic().getEncoded();
    }

    /** Igual que {@link #getPublicKeyEncoded()} pero devuelve null si no hay par aun. */
    public byte[] getPublicKeyEncodedOrNull() {
        return keyPair == null ? null : keyPair.getPublic().getEncoded();
    }

    /**
     * Deriva la clave AES-256 compartida a partir de la clave publica del otro
     * usuario (recibida via KEY_EXCHANGE).
     */
    public void deriveSharedKey(byte[] peerPublicKeyEncoded) throws Exception {
        if (keyPair == null) throw new IllegalStateException("Llama a generateKeyPair() primero");

        KeyFactory kf = KeyFactory.getInstance(DH);
        PublicKey peerKey = kf.generatePublic(new X509EncodedKeySpec(peerPublicKeyEncoded));

        KeyAgreement ka = KeyAgreement.getInstance(DH);
        ka.init(keyPair.getPrivate());
        ka.doPhase(peerKey, true);
        byte[] sharedSecret = ka.generateSecret();

        // Derivar 256 bits estables a partir del secreto compartido.
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(sharedSecret); // 32 bytes = AES-256
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
    }

    public boolean isReady() {
        return aesKey != null;
    }

    /**
     * Cifra con AES-256-CBC. Devuelve {@code IV(16) || ciphertext}.
     */
    public byte[] encrypt(byte[] plaintext) throws Exception {
        requireKey();
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_CBC);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext);

        byte[] out = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LEN);
        System.arraycopy(ct, 0, out, IV_LEN, ct.length);
        return out;
    }

    /**
     * Descifra un payload con formato {@code IV(16) || ciphertext}.
     */
    public byte[] decrypt(byte[] ivAndCiphertext) throws Exception {
        requireKey();
        if (ivAndCiphertext.length < IV_LEN) {
            throw new IllegalArgumentException("Payload cifrado demasiado corto");
        }
        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LEN);
        byte[] ct = Arrays.copyOfRange(ivAndCiphertext, IV_LEN, ivAndCiphertext.length);

        Cipher cipher = Cipher.getInstance(AES_CBC);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
        return cipher.doFinal(ct);
    }

    private void requireKey() {
        if (aesKey == null) {
            throw new IllegalStateException("Clave compartida no derivada todavia");
        }
    }

    // ----- Utilidad: hash de contrasena en el cliente (Seguridad de Auth) -----

    /** SHA-256(password) en hex. El servidor aplica su propio salt por usuario. */
    public static String hashPassword(String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] d = sha.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}