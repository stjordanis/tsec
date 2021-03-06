package tsec.cipher.symmetric.libsodium.internal

import cats.Id
import cats.effect.Sync
import tsec.cipher.symmetric._
import tsec.cipher.symmetric.libsodium.SodiumKey
import tsec.cipher.symmetric.libsodium.SodiumCipherError._
import tsec.keygen.symmetric._
import tsec.libsodium.ScalaSodium

trait SodiumAEADPlatform[A] extends SodiumKeyGenerator[A] with SodiumAEADCipher[A] with SodiumAEADAPI[A] {

  def defaultIvGen[F[_]](implicit F: Sync[F], S: ScalaSodium): IvGen[F, A] = new IvGen[F, A] {
    def genIv: F[Iv[A]] = F.delay {
      val nonce = Iv[A](new Array[Byte](nonceLen))
      S.randombytes_buf(nonce, nonceLen)
      nonce
    }

    def genIvUnsafe: Iv[A] = {
      val nonce = Iv[A](new Array[Byte](nonceLen))
      S.randombytes_buf(nonce, nonceLen)
      nonce
    }
  }

  implicit def genEncryptor[F[_]](implicit F: Sync[F], S: ScalaSodium): AADEncryptor[F, A, SodiumKey] =
    new AADEncryptor[F, A, SodiumKey] {

      def encrypt(plainText: PlainText, key: SodiumKey[A], iv: Iv[A]): F[CipherText[A]] =
        F.delay(impl.unsafeEncrypt(plainText, key, iv))

      def decrypt(cipherText: CipherText[A], key: SodiumKey[A]): F[PlainText] =
        F.delay(impl.unsafeDecrypt(cipherText, key))

      def encryptDetached(plainText: PlainText, key: SodiumKey[A], iv: Iv[A]): F[(CipherText[A], AuthTag[A])] =
        F.delay(impl.unsafeEncryptDetached(plainText, key, iv))

      def decryptDetached(cipherText: CipherText[A], key: SodiumKey[A], authTag: AuthTag[A]): F[PlainText] =
        F.delay(impl.unsafeDecryptDetached(cipherText, key, authTag))

      def encryptWithAAD(plainText: PlainText, key: SodiumKey[A], iv: Iv[A], aad: AAD): F[CipherText[A]] =
        F.delay(impl.unsafeEncryptAAD(plainText, key, iv, aad))

      def decryptWithAAD(cipherText: CipherText[A], key: SodiumKey[A], aad: AAD): F[PlainText] =
        F.delay(impl.unsafeDecryptAAD(cipherText, key, aad))

      def encryptWithAADDetached(
          plainText: PlainText,
          key: SodiumKey[A],
          iv: Iv[A],
          aad: AAD
      ): F[(CipherText[A], AuthTag[A])] =
        F.delay(impl.unsafeEncryptAADDetached(plainText, key, iv, aad))

      def decryptWithAADDetached(
          cipherText: CipherText[A],
          key: SodiumKey[A],
          aad: AAD,
          authTag: AuthTag[A]
      ): F[PlainText] = F.delay(impl.unsafeDecryptAADDetached(cipherText, key, authTag, aad))
    }

  implicit def genKeyF[F[_]](implicit F: Sync[F], S: ScalaSodium): SymmetricKeyGen[F, A, SodiumKey] =
    new SymmetricKeyGen[F, A, SodiumKey] {
      def generateKey: F[SodiumKey[A]] =
        F.delay(impl.generateKeyUnsafe)

      def build(rawKey: Array[Byte]): F[SodiumKey[A]] = F.delay(impl.buildKeyUnsafe(rawKey))
    }

  implicit def unsafeKeyGen(implicit S: ScalaSodium): IdKeyGen[A, SodiumKey] =
    new IdKeyGen[A, SodiumKey] {
      def generateKey: Id[SodiumKey[A]] = impl.generateKeyUnsafe

      def build(rawKey: Array[Byte]): Id[SodiumKey[A]] = impl.buildKeyUnsafe(rawKey)
    }

  object impl {
    final def generateKeyUnsafe(implicit S: ScalaSodium): SodiumKey[A] = {
      val bytes = new Array[Byte](keyLength)
      S.randombytes_buf(bytes, keyLength)
      SodiumKey[A](bytes)
    }

    final def buildKeyUnsafe(key: Array[Byte])(implicit S: ScalaSodium): SodiumKey[A] =
      if (key.length != keyLength)
        throw CipherKeyError("Invalid key length")
      else
        SodiumKey[A](key)

    final def unsafeEncrypt(plaintext: PlainText, key: SodiumKey[A], nonce: Iv[A])(
        implicit S: ScalaSodium
    ): CipherText[A] = {
      val outArray = RawCipherText[A](new Array[Byte](plaintext.length + authTagLen))

      if (sodiumEncrypt(outArray, plaintext, nonce, key) != 0)
        throw EncryptError("Invalid encryption Info")

      CipherText[A](outArray, nonce)
    }

    final def unsafeDecrypt(cipherText: CipherText[A], key: SodiumKey[A])(
        implicit S: ScalaSodium
    ): PlainText = {
      val originalMessage = new Array[Byte](cipherText.content.length - authTagLen)
      if (sodiumDecrypt(originalMessage, cipherText, key) != 0)
        throw DecryptError("Invalid Decryption info")
      PlainText(originalMessage)
    }

    final def unsafeEncryptDetached(plainText: PlainText, key: SodiumKey[A], nonce: Iv[A])(
        implicit
        S: ScalaSodium
    ): (CipherText[A], AuthTag[A]) = {
      val outArray = RawCipherText[A](new Array[Byte](plainText.length))
      val macOut   = new Array[Byte](authTagLen)
      if (sodiumEncryptDetached(outArray, macOut, plainText, nonce, key) != 0)
        throw EncryptError("Invalid encryption Info")

      (CipherText[A](outArray, nonce), AuthTag[A](macOut))
    }

    final def unsafeDecryptDetached(
        cipherText: CipherText[A],
        key: SodiumKey[A],
        authTag: AuthTag[A]
    )(implicit S: ScalaSodium): PlainText = {
      val originalMessage = new Array[Byte](cipherText.content.length)
      if (sodiumDecryptDetached(originalMessage, cipherText, authTag, key) != 0)
        throw DecryptError("Invalid Decryption info")
      PlainText(originalMessage)
    }

    final def unsafeEncryptAAD(plaintext: PlainText, key: SodiumKey[A], nonce: Iv[A], aad: AAD)(
        implicit S: ScalaSodium
    ): CipherText[A] = {
      val outArray = RawCipherText[A](new Array[Byte](plaintext.length + authTagLen))

      if (sodiumEncryptAAD(outArray, plaintext, nonce, key, aad) != 0)
        throw EncryptError("Invalid encryption Info")

      CipherText[A](outArray, nonce)
    }

    final def unsafeDecryptAAD(cipherText: CipherText[A], key: SodiumKey[A], aad: AAD)(
        implicit S: ScalaSodium
    ): PlainText = {
      val originalMessage = new Array[Byte](cipherText.content.length - authTagLen)
      if (sodiumDecryptAAD(originalMessage, cipherText, key, aad) != 0)
        throw DecryptError("Invalid Decryption info")
      PlainText(originalMessage)
    }

    final def unsafeEncryptAADDetached(plainText: PlainText, key: SodiumKey[A], nonce: Iv[A], aad: AAD)(
        implicit
        S: ScalaSodium
    ): (CipherText[A], AuthTag[A]) = {
      val outArray = RawCipherText[A](new Array[Byte](plainText.length))
      val macOut   = new Array[Byte](authTagLen)
      if (sodiumEncryptDetachedAAD(outArray, macOut, plainText, nonce, key, aad) != 0)
        throw EncryptError("Invalid encryption Info")

      (CipherText[A](outArray, nonce), AuthTag[A](macOut))
    }

    final def unsafeDecryptAADDetached(
        cipherText: CipherText[A],
        key: SodiumKey[A],
        authTag: AuthTag[A],
        aad: AAD
    )(implicit S: ScalaSodium): PlainText = {
      val originalMessage = new Array[Byte](cipherText.content.length)
      if (sodiumDecryptDetachedAAD(originalMessage, cipherText, authTag, key, aad) != 0)
        throw DecryptError("Invalid Decryption info")
      PlainText(originalMessage)
    }
  }
}
