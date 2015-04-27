package akka.contrib.http

import akka.http.scaladsl.common.NameReceptacle
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.{ Directive0, Directive1, MalformedQueryParamRejection, ValidationRejection }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.unmarshalling.{ FromStringUnmarshaller => FSU, Unmarshaller }
import scala.util.{ Failure, Success }

object Directives {

  /**
   * Continues to the inner route only if the `Accept` header
   * contains the given media type.
   */
  def accept(mediaType: MediaType): Directive0 =
    optionalHeaderValueByType[Accept](()).flatMap { accept =>
      accept.flatMap(_.mediaRanges.collectFirst {
        case range if range.matches(mediaType) => pass
      }).getOrElse(reject)
    }

  /**
   * Gets a list of parameters specified by a name and a type.
   *
   * Magnet pattern is used to keep the directive signature implicit
   * parameters free, which makes usage of the directive cleaner.
   */
  def parameterList(pm: ParametersListMagnet): pm.Out = pm()

  implicit def fromNameReceptacle[T](nr: NameReceptacle[T])(implicit fsu: FSU[T]) =
    new ParametersListMagnet {
      type Out = Directive1[List[T]]
      def apply() =
        parameterMultiMap.flatMap { multiMap =>
          val parameterList = multiMap.getOrElse(nr.name, List.empty).map { value =>
            onComplete(fsu(value)) flatMap {
              case Success(x) => provide(x)
              case Failure(x) => reject(MalformedQueryParamRejection(nr.name, x.getMessage, Option(x.getCause))).toDirective(Tuple.forTuple1[T])
            }
          }

          Directive.sequence(parameterList)
        }
    }

  sealed trait ParametersListMagnet {
    type Out
    def apply(): Out
  }

  object Directive {
    def sequence[T](directives: List[Directive1[T]]) =
      directives.foldLeft(provide(List.empty[T])) {
        (dl, da) => dl.flatMap { l => da.flatMap(a => provide(l :+ a)) }
      }
  }
}
