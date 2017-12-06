package tsec.libsodium.hashing

import tsec.libsodium.ScalaSodium
import tsec.libsodium.hashing.internal.SodiumHashPlatform

sealed trait SodiumSHA512

object SodiumSHA512 extends SodiumHashPlatform[SodiumSHA512] {
  val hashLen: Int = 64

  val algorithm: String = "SHA512"

  def stateSize(implicit S: ScalaSodium): Int = S.crypto_hash_sha512_statebytes

  def sodiumHash(in: Array[Byte], out: Array[Byte])(implicit S: ScalaSodium): Int =
    S.crypto_hash_sha512(out, in, in.length)

  def sodiumHashInit(state: HashState[SodiumSHA512])(implicit S: ScalaSodium): Int =
    S.crypto_hash_sha512_init(state)

  def sodiumHashChunk(state: HashState[SodiumSHA512], in: Array[Byte])(implicit S: ScalaSodium): Int =
    S.crypto_hash_sha512_update(state, in, in.length)

  def sodiumHashFinal(state: HashState[SodiumSHA512], out: Array[Byte])(implicit S: ScalaSodium): Int =
    S.crypto_hash_sha512_final(state, out)
}
