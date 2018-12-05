package ru.serbis.okto.sv_blg.service

import akka.actor.{Actor, ActorRef, Props}
import ru.serbis.okto.sv_blg.proxy.http.HttpProxy
import ru.serbis.okto.sv_blg.proxy.system.ActorSystemProxy
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger

//TODO нудно добавить кеш, что бы не лезть при каждом запросе к sv_sec

/** Security system actor. It is intended to obtain access rights to resources */
object PermissionsChecker {

  /** @param svSecBase sv_sec microservice http path base. For example https://okto.su:5100
    * @param systemProxy actor system proxy
    * @param httpProxy http proxy
    */
  def props(svSecBase: String, systemProxy: ActorSystemProxy, httpProxy: HttpProxy) = Props(new PermissionsChecker(svSecBase, systemProxy, httpProxy))

  /** The function through which you can initiate the execution of application code with rights verification via sv_sec. For
    * details read description for the CheckPing message
    *
    * @param session user session
    * @param resource checked resource path
    * @param f lambda with app code
    * @param sender actor sender
    * @param self actor ref
    * @param permChecker ref to the PermissionsChecker actor
    */
  def withPerms(session: Option[String], resource: String)(f: (Option[String], ActorRef) => Unit)(sender: ActorRef, self: ActorRef, permChecker: ActorRef): Unit = {
    permChecker.tell(PermissionsChecker.Commands.CheckPing(session, resource, sender,  f), self)
  }

  object Commands {

    /** This command starts checking the rights to access the resource in ping-pong mode. In order for an actor to use the
      * given mode, it needs in Receive to declare the following message handlers:
      *
      * case CheckPong(permissions, orig, f) => f(Some(permissions), orig)
      * case InternalError(_, orig, f) => f(None, orig)
      *
      * And a piece of code for which verification of access rights to a resource is required should be wrapped in the
      * following way:
      *
      * withPerms(session, "resource_name") { (perms, orig) =>
      *   some app code
      * }
      *
      * In this function variable perms describes permissions for the resource and orig contain original sender actor ref.
      *
      * Under the hood, it works like this. When the withPerms function is called, it sends a CheckPing message to the
      * PermissionsChecker actor. This message contains all the environmental information about the environment from which
      * the message was sent and the data required for security checking. After receiving this message, PermissionsChecker
      * transfers control of the underlying fsm, which interacts with the sv_sec microservice. Depending on the result of
      * the operation, it sends either a CheckPong message or a InternalError to the actor that initiated the call to the
      * withPerms function. Since the message data handlers are declared to it, the body of the withPerms function with the
      * application code will be called, and as perms, either Some with rights from sv_sec or None will be transferred, which
      * is a sign that the rights were not obtained due to an error.
      *
      * @param session checked user session
      * @param resource checked resource
      * @param orig original sender of the actor with call withPerms function
      * @param f lambda passed to the withPerms function with app code
      */
    case class CheckPing(session: Option[String], resource: String, orig: ActorRef, f: (Option[String], ActorRef) => Unit)
  }

  object Responses {

    /** Reverse message for the ping-pong operation. For details read description for the CheckPing message
      *
      * @param permissions received permissions from sv_sec
      * @param orig original sender of the actor with call withPerms function
      * @param f lambda passed to the withPerms function with app code
      */
    case class CheckPong(permissions: String, orig: ActorRef, f: (Option[String], ActorRef) => Unit)

    /** Some internal error was occurred at permissions checking process */
    case class InternalError(reason: String, orig: ActorRef, f: (Option[String], ActorRef) => Unit)
  }
}

class PermissionsChecker(svSecBase: String, systemProxy: ActorSystemProxy, httpProxy: HttpProxy) extends Actor with StreamLogger {
  import PermissionsChecker.Commands._

  setLogSourceName(s"PermissionsChecker")
  setLogKeys(Seq("PermissionsChecker"))

  implicit val logQualifier = LogEntryQualifier("static")

  override def receive: Receive = {

    /** See the message description*/
    case CheckPing(session, resource, orig, f) =>
      implicit val logQualifier = LogEntryQualifier("CheckPing")

      logger.info(s"Initiated security check ping-pong operation for session '$session' and resource '$resource'")
      val fsm = systemProxy.actorOf(PermissionsCheckerFsm.props(svSecBase, httpProxy))
      fsm.tell(PermissionsCheckerFsm.Commands.Exec(session, resource, orig, f), sender())
  }
}
