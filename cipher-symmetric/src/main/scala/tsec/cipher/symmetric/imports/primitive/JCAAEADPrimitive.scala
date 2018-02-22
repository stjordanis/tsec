package tsec.cipher.symmetric.imports.primitive

import java.util.concurrent.{ConcurrentLinkedQueue => JQueue}
import java.util.{Arrays => JaRule}
import javax.crypto.{Cipher => JCipher}

import cats.MonadError
import cats.effect.Sync
import cats.syntax.all._
import tsec.cipher.common.padding.SymmetricPadding
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.core._
import tsec.cipher.symmetric.imports._
import tsec.common.CanCatch

sealed abstract class JCAAEADPrimitive[F[_], A, M, P](private val queue: JQueue[JCipher])(
    implicit algoTag: BlockCipher[A],
    aead: AEADCipher[A],
    modeSpec: CipherMode[M],
    paddingTag: SymmetricPadding[P],
    F: MonadError[F, Throwable],
    private[tsec] val ivProcess: IvProcess[A, M, P],
) extends AuthEncryptor[F, A, SecretKey]
    with CanCatch[F] {

  private def getInstance: JCipher = {
    val instance = queue.poll()
    if (instance != null)
      instance
    else
      JCAPrimitiveCipher.getJCipherUnsafe[A, M, P]
  }

  def encrypt(plainText: PlainText, key: SecretKey[A], iv: Iv[A]): F[CipherText[A]] =
    catchF {
      val instance = getInstance
      ivProcess.encryptInit(instance, iv, key.toJavaKey)
      val encrypted = instance.doFinal(plainText)
      queue.add(instance)
      CipherText[A](RawCipherText(encrypted), iv)
    }

  def decrypt(cipherText: CipherText[A], key: SecretKey[A]): F[PlainText] =
    catchF {
      val instance = getInstance
      ivProcess.decryptInit(instance, cipherText.nonce, key.toJavaKey)
      val out = instance.doFinal(cipherText.content)
      queue.add(instance)
      PlainText(out)
    }

  def encryptWithAAD(plainText: PlainText, key: SecretKey[A], iv: Iv[A], aad: AAD): F[CipherText[A]] =
    catchF {
      val instance = getInstance
      ivProcess.encryptInit(instance, iv, key.toJavaKey)
      instance.updateAAD(aad)
      val encrypted = RawCipherText[A](instance.doFinal(plainText))
      queue.add(instance)
      CipherText[A](encrypted, iv)
    }

  def decryptWithAAD(cipherText: CipherText[A], key: SecretKey[A], aad: AAD): F[PlainText] =
    catchF {
      val instance = getInstance
      ivProcess.decryptInit(instance, cipherText.nonce, key.toJavaKey)
      instance.updateAAD(aad)
      val out = instance.doFinal(cipherText.content)
      queue.add(instance)
      PlainText(out)
    }

  def encryptDetached(plainText: PlainText, key: SecretKey[A], iv: Iv[A]): F[(CipherText[A], AuthTag[A])] =
    catchF {
      val instance = getInstance
      ivProcess.encryptInit(instance, iv, key.toJavaKey)
      val encrypted = instance.doFinal(plainText)
      queue.add(instance)
      val cipherText = RawCipherText[A](JaRule.copyOfRange(encrypted, 0, encrypted.length - aead.tagSizeBytes))
      val tag        = JaRule.copyOfRange(encrypted, encrypted.length - aead.tagSizeBytes, encrypted.length)
      (CipherText[A](cipherText, iv), AuthTag[A](tag))
    }

  def decryptDetached(cipherText: CipherText[A], key: SecretKey[A], tag: AuthTag[A]): F[PlainText] =
    if (tag.length != aead.tagSizeBytes)
      F.raiseError(AuthTagError("Authentication tag of incorrect length"))
    else
      catchF {
        val instance = getInstance
        ivProcess.decryptInit(instance, cipherText.nonce, key.toJavaKey)
        //Re-combine the auth tag and the ciphertext, because thx JCA
        val combined = new Array[Byte](aead.tagSizeBytes + cipherText.content.length)
        System.arraycopy(cipherText.content, 0, combined, 0, cipherText.content.length)
        System.arraycopy(tag, 0, combined, cipherText.content.length, tag.length)

        val out = instance.doFinal(combined)
        queue.add(instance)
        PlainText(out)
      }

  def encryptWithAADDetached(
      plainText: PlainText,
      key: SecretKey[A],
      iv: Iv[A],
      aad: AAD
  ): F[(CipherText[A], AuthTag[A])] =
    catchF {
      val instance = getInstance
      ivProcess.encryptInit(instance, iv, key.toJavaKey)
      instance.updateAAD(aad)
      val encrypted = instance.doFinal(plainText)
      queue.add(instance)
      val cipherText = RawCipherText[A](JaRule.copyOfRange(encrypted, 0, encrypted.length - aead.tagSizeBytes))
      val tag        = JaRule.copyOfRange(encrypted, encrypted.length - aead.tagSizeBytes, encrypted.length)
      (CipherText[A](cipherText, iv), AuthTag[A](tag))
    }

  def decryptWithAADDetached(cipherText: CipherText[A], key: SecretKey[A], aad: AAD, tag: AuthTag[A]): F[PlainText] =
    if (tag.length != aead.tagSizeBytes)
      F.raiseError(AuthTagError("Authentication tag of incorrect length"))
    else
      catchF {
        val instance = getInstance
        ivProcess.decryptInit(instance, cipherText.nonce, key.toJavaKey)

        //Re-combine the auth tag and the ciphertext, because thx JCA
        val combined = new Array[Byte](aead.tagSizeBytes + cipherText.content.length)
        System.arraycopy(cipherText.content, 0, combined, 0, cipherText.content.length)
        System.arraycopy(tag, 0, combined, cipherText.content.length, tag.length)

        instance.updateAAD(aad)
        val out = instance.doFinal(combined)
        queue.add(instance)
        PlainText(out)
      }

}

object JCAAEADPrimitive {

  private[tsec] def sync[F[_], A: BlockCipher: AEADCipher, M: CipherMode, P: SymmetricPadding](
      queueSize: Int = 15
  )(implicit F: Sync[F], ivProcess: IvProcess[A, M, P]): F[AuthEncryptor[F, A, SecretKey]] =
    F.delay(JCAPrimitiveCipher.genQueueUnsafe[A, M, P](queueSize))
      .map(new JCAAEADPrimitive[F, A, M, P](_) {
        def catchF[C](thunk: => C): F[C] = F.delay(thunk)
      })

  private[tsec] def monadError[F[_], A: BlockCipher: AEADCipher, M: CipherMode, P: SymmetricPadding](
      queueSize: Int = 15
  )(implicit F: MonadError[F, Throwable], ivProcess: IvProcess[A, M, P]): F[AuthEncryptor[F, A, SecretKey]] =
    F.catchNonFatal(JCAPrimitiveCipher.genQueueUnsafe[A, M, P](queueSize))
      .map(
        q =>
          new JCAAEADPrimitive[F, A, M, P](q) {
            def catchF[C](thunk: => C): F[C] =
              F.catchNonFatal(thunk)
        }
      )
}
