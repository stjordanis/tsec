package tsec

import java.util.UUID

import cats.{Applicative, Monad}
import cats.data.{Kleisli, OptionT}
import org.http4s._
import org.http4s.server.Middleware
import org.http4s.headers.{Authorization, Cookie => C}
import cats.instances.all._
import cats.syntax.eq._
import cats.syntax.either._
import io.circe.Decoder.Result
import io.circe._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

package object authentication {

  trait BackingStore[F[_], I, V] {
    def put(elem: V): F[V]

    def get(id: I): OptionT[F, V]

    def update(v: V): F[V]

    def delete(id: I): F[Unit]
  }

  type AuthExtractorService[F[_], Ident, Auth] = Kleisli[OptionT[F, ?], Request[F], SecuredRequest[F, Ident, Auth]]

  /** Inspired from the Silhouette `SecuredRequest`
    *
    */
  final case class SecuredRequest[F[_], Identity, Auth](request: Request[F], identity: Identity, authenticator: Auth)

  object asAuthed {

    /** Matcher for the http4s dsl
      * @param ar
      * @tparam F
      * @tparam A
      * @tparam I
      * @return
      */
    def unapply[F[_], I, A](ar: SecuredRequest[F, I, A]): Option[(Request[F], I)] =
      Some(ar.request -> ar.identity)
  }

  type TSecMiddleware[F[_], I, A] =
    Middleware[OptionT[F, ?], SecuredRequest[F, I, A], Response[F], Request[F], Response[F]]

  object TSecMiddleware {
    def apply[F[_]: Monad, Ident, Auth](
        authedStuff: Kleisli[OptionT[F, ?], Request[F], SecuredRequest[F, Ident, Auth]]
    ): TSecMiddleware[F, Ident, Auth] =
      service => {
        service.compose(authedStuff)
      }
  }

  type TSecAuthService[F[_], Ident, A] = Kleisli[OptionT[F, ?], SecuredRequest[F, Ident, A], Response[F]]

  object TSecAuthService {

    /** Lifts a partial function to an `TSecAuthedService`.  Responds with
      * [[org.http4s.Response.notFound]], which generates a 404, for any request
      * where `pf` is not defined.
      */
    def apply[F[_], I, A](
        pf: PartialFunction[SecuredRequest[F, I, A], F[Response[F]]]
    )(implicit F: Monad[F]): TSecAuthService[F, I, A] =
      Kleisli(req => pf.andThen(OptionT.liftF(_)).applyOrElse(req, Function.const(OptionT.none)))

    def apply[F[_], I, A](
        pf: PartialFunction[SecuredRequest[F, I, A], F[Response[F]]],
        andThen: (Response[F], A) => OptionT[F, Response[F]]
    )(implicit F: Monad[F]): TSecAuthService[F, I, A] =
      Kleisli(
        req =>
          pf.andThen(OptionT.liftF(_))
            .applyOrElse(req, Function.const(OptionT.none[F, Response[F]]))
            .flatMap(r => andThen(r, req.authenticator))
      )

    /** The empty service (all requests fallthrough).
      * @tparam F - Ignored
      * @tparam Ident - Ignored
      * @tparam A - Ignored
      * @return
      */
    def empty[F[_]: Applicative, Ident, A]: TSecAuthService[F, Ident, A] =
      Kleisli.lift(OptionT.none)
  }

  /** Common cookie settings for cookie-based authenticators
    *
    * @param cookieName
    * @param secure
    * @param httpOnly
    * @param domain
    * @param path
    * @param extension
    */
  final case class TSecCookieSettings(
      cookieName: String = "tsec-auth-cookie",
      secure: Boolean,
      httpOnly: Boolean = true,
      domain: Option[String] = None,
      path: Option[String] = None,
      extension: Option[String] = None,
      expiryDuration: FiniteDuration,
      maxIdle: Option[FiniteDuration]
  )

  final case class TSecTokenSettings(
      expirationTime: FiniteDuration,
      maxIdle: Option[FiniteDuration]
  )

  final case class TSecJWTSettings(
      headerName: String = "X-TSec-JWT",
      expirationTime: FiniteDuration,
      maxIdle: Option[FiniteDuration]
  )

  def cookieFromRequest[F[_]: Monad](name: String, request: Request[F]): OptionT[F, Cookie] =
    OptionT.fromOption[F](C.from(request.headers).flatMap(_.values.find(_.name === name)))

  def extractBearerToken[F[_]: Monad](request: Request[F]): Option[String] =
    request.headers.get(Authorization).flatMap { t =>
      t.credentials match {
        case Credentials.Token(scheme, token) if scheme == AuthScheme.Bearer =>
          Some(token)
        case _ => None
      }
    }

  implicit val HttpDateLongDecoder: Decoder[HttpDate] = new Decoder[HttpDate] {
    def apply(c: HCursor): Result[HttpDate] =
      c.value.as[Long].flatMap(HttpDate.fromEpochSecond(_).leftMap(_ => DecodingFailure("InvalidEpoch", Nil)))
  }

  implicit val HttpDateLongEncoder: Encoder[HttpDate] = new Encoder[HttpDate] {
    def apply(a: HttpDate): Json = Json.fromLong(a.epochSecond)
  }

  def uuidFromRaw[F[_]: Applicative](string: String): OptionT[F, UUID] =
    try OptionT.pure(UUID.fromString(string))
    catch {
      case NonFatal(e) => OptionT.none
    }
}
