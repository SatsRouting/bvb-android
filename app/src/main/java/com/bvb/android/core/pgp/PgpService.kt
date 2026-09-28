package com.bvb.android.core.pgp

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.util.io.Streams
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.util.Passphrase

/**
 * PGP operations mirroring the web client (frontend/src/utils/pgp.js):
 * armored message encrypt/decrypt, detached signatures, binary attachments,
 * and the dual-encryption envelope used for trade chat.
 */
@Singleton
class PgpService @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    fun encryptMessage(plaintext: String, recipientPublicKeyArmored: String): String {
        val publicKey = PGPainless.readKeyRing().publicKeyRing(recipientPublicKeyArmored)
            ?: throw IllegalArgumentException("Invalid recipient public key")
        val out = ByteArrayOutputStream()
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.encrypt(EncryptionOptions.encryptCommunications().addRecipient(publicKey))
                    .setAsciiArmor(true)
            )
        stream.write(plaintext.toByteArray(Charsets.UTF_8))
        stream.close()
        return out.toString(Charsets.UTF_8.name())
    }

    fun decryptMessage(armoredMessage: String, privateKeyArmored: String, passphrase: String): String {
        val bytes = decrypt(armoredMessage.byteInputStream(), privateKeyArmored, passphrase)
        return bytes.toString(Charsets.UTF_8)
    }

    fun encryptBinary(data: ByteArray, recipientPublicKeyArmored: String): ByteArray {
        val publicKey = PGPainless.readKeyRing().publicKeyRing(recipientPublicKeyArmored)
            ?: throw IllegalArgumentException("Invalid recipient public key")
        val out = ByteArrayOutputStream()
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.encrypt(EncryptionOptions.encryptCommunications().addRecipient(publicKey))
                    .setAsciiArmor(false)
            )
        stream.write(data)
        stream.close()
        return out.toByteArray()
    }

    fun decryptBinary(encrypted: ByteArray, privateKeyArmored: String, passphrase: String): ByteArray =
        decrypt(encrypted.inputStream(), privateKeyArmored, passphrase)

    fun signDetached(plaintext: String, privateKeyArmored: String, passphrase: String): String {
        val secretKey = PGPainless.readKeyRing().secretKeyRing(privateKeyArmored)
            ?: throw IllegalArgumentException("Invalid private key")
        val protector = SecretKeyRingProtector.unlockAnyKeyWith(Passphrase.fromPassword(passphrase))
        val out = ByteArrayOutputStream()
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.sign(
                    SigningOptions.get().addDetachedSignature(protector, secretKey)
                ).setAsciiArmor(true)
            )
        stream.write(plaintext.toByteArray(Charsets.UTF_8))
        stream.close()
        val result = stream.result
        val signatures = result.detachedSignatures.flatten()
        val sigOut = ByteArrayOutputStream()
        val armorOut = org.bouncycastle.bcpg.ArmoredOutputStream(sigOut)
        signatures.first().encode(armorOut)
        armorOut.close()
        return sigOut.toString(Charsets.UTF_8.name())
    }

    fun verifyDetached(plaintext: String, armoredSignature: String, senderPublicKeyArmored: String): Boolean {
        return try {
            val publicKey = PGPainless.readKeyRing().publicKeyRing(senderPublicKeyArmored) ?: return false
            val stream = PGPainless.decryptAndOrVerify()
                .onInputStream(plaintext.byteInputStream())
                .withOptions(
                    ConsumerOptions.get()
                        .addVerificationCert(publicKey)
                        .addVerificationOfDetachedSignatures(armoredSignature.byteInputStream())
                )
            Streams.drain(stream)
            stream.close()
            stream.metadata.isVerifiedSigned
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Trade-chat messages are stored dual-encrypted as a JSON envelope
     * {"for_recipient": "...", "for_sender": "..."}; pick the copy readable by
     * the current user (legacy messages are a bare armored string).
     */
    fun extractEncryptionForUser(encryptedContent: String, currentUserId: String, senderUserId: String): String {
        return try {
            val obj = json.parseToJsonElement(encryptedContent).jsonObject
            val forRecipient = obj["for_recipient"]?.jsonPrimitive?.content
            val forSender = obj["for_sender"]?.jsonPrimitive?.content
            if (forRecipient != null && forSender != null) {
                if (currentUserId == senderUserId) forSender else forRecipient
            } else {
                encryptedContent
            }
        } catch (e: Exception) {
            encryptedContent
        }
    }

    fun isValidPgpMessage(text: String?): Boolean =
        text != null && text.contains("-----BEGIN PGP MESSAGE-----") && text.contains("-----END PGP MESSAGE-----")

    private fun decrypt(input: java.io.InputStream, privateKeyArmored: String, passphrase: String): ByteArray {
        val secretKey = PGPainless.readKeyRing().secretKeyRing(privateKeyArmored)
            ?: throw IllegalArgumentException("Invalid private key")
        val protector = SecretKeyRingProtector.unlockAnyKeyWith(Passphrase.fromPassword(passphrase))
        val stream = PGPainless.decryptAndOrVerify()
            .onInputStream(input)
            .withOptions(
                ConsumerOptions.get().addDecryptionKey(secretKey, protector)
            )
        val out = ByteArrayOutputStream()
        Streams.pipeAll(stream, out)
        stream.close()
        return out.toByteArray()
    }
}
