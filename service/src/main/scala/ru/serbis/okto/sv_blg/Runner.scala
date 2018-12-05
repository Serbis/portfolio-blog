package ru.serbis.okto.sv_blg

import java.io.{File, FileInputStream, InputStream}
import java.nio.file.Files
import java.security.{KeyStore, SecureRandom}

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.Http.ServerBinding
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import ru.serbis.okto.sv_blg.domain.DomainJsonProtocol
import ru.serbis.okto.sv_blg.domain.entry.EntryAggregate
import ru.serbis.okto.sv_blg.domain.entry.read.{EntryView, EntryViewBuilder}
import ru.serbis.okto.sv_blg.endpoint.HttpRoute
import ru.serbis.okto.sv_blg.proxy.files.RealFilesProxy
import ru.serbis.okto.sv_blg.proxy.http.RealHttpProxy
import ru.serbis.okto.sv_blg.proxy.system.RealActorSystemProxy
import ru.serbis.okto.sv_blg.reps.ConfigRep
import ru.serbis.okto.sv_blg.reps.ConfigRep.Responses.RuntimeConfig
import ru.serbis.okto.sv_blg.service.{PermissionsChecker, TextResourceRepository}
import ru.serbis.svc.FsmDefaults.{Data, State}
import ru.serbis.svc.ddd.ElasticsearchRepository
import ru.serbis.svc.logger.Logger.{LogEntryQualifier, LogLevels}
import ru.serbis.svc.logger.{FileLogger, StdOutLogger, StreamLogger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/** This actor performs initial initialization of the microservice */
object Runner {

  /** n/c */
  def props: Props = Props(new Runner)

  object States {

    /** see description of the state code */
    case object Idle extends State

    /** see description of the state code */
    case object Configure extends State

    /** see description of the state code */
    case object WaitServerBinding extends State
  }

  object StatesData {

    /** n/c */
    case object Uninitialized extends Data

    /** @param workDir microservice work directory */
    case class Configuring(workDir: String, configRep: ActorRef) extends Data

    /** n/c */
    case class WaitingServerBinding() extends Data
  }

  object Commands {

    /** @param workDir microservice work directory */
    case class Exec(workDir: String)
  }
}

class Runner extends FSM[State, Data] with StreamLogger with DomainJsonProtocol {
  import Runner.Commands._
  import Runner.States._
  import Runner.StatesData._

  initializeGlobalLogger(context.system, LogLevels.Info)
  logger.addDestination(context.system.actorOf(StdOutLogger.props, "StdOutLogger"))
  setLogSourceName(s"Runner*${self.path.name}")
  setLogKeys(Seq("Runner"))

  implicit val logQualifier = LogEntryQualifier("static")

  startWith(Idle, Uninitialized)

  /** Starting state. It initializes the repository block. Then, in the repository of the service.conf file, a request is
    * sent for the runtime configuration object. The timeout for the Exec message is a fatal error that stops the program. */
  when(Idle, 30 second) {
    case Event(req: Exec, _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_Exec")

      val configRep = context.system.actorOf(ConfigRep.props(s"${req.workDir}/service.conf"), "ConfigRep")
      configRep ! ConfigRep.Commands.Get
      goto(Configure) using Configuring(req.workDir, configRep)

    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_StateTimeout")
      logger.fatal("Exec command wait timeout")
      context.system.terminate()
      stop
  }

  /** At this stage, the configuration object is received. When it is successfully received, the logger configures and
    * the server binding process stared. The case when the configuration object does not come is a fatal error. */
  when(Configure, 30 second) {
    case Event(cfg: RuntimeConfig, data: Configuring) =>
      implicit val logQualifier = LogEntryQualifier("Configure_RuntimeConfig")

      //--- logging system

      logger.info(s"Set global log level to '${cfg.cfg.logConfig.level}'")
      setGlobalLogLevel(cfg.cfg.logConfig.level)
      logger.info(s"Set global log keys to ' ${cfg.cfg.logConfig.keys.foldLeft("")((a, v) => a + s"$v ")}'")
      setGlobalLogKeys(cfg.cfg.logConfig.keys)
      logger.info(s"Set log file path to '${cfg.cfg.logConfig.path}'")
      logger.info(s"Set log file truncating to '${cfg.cfg.logConfig.truncate}'")
      logger.addDestination(context.system.actorOf(FileLogger.props(cfg.cfg.logConfig.path, cfg.cfg.logConfig.truncate), "FileLogger"))
      logger.info(s"--------------------------------------")


      //--- permchecker
      logger.info(s"Set sv_sec path to '${cfg.cfg.securityConfig.svSecUri}'")
      val permsChecker = context.system.actorOf(PermissionsChecker.props(cfg.cfg.securityConfig.svSecUri, new RealActorSystemProxy(context.system), new RealHttpProxy()(context.system)))

      //--- text resources repository
      logger.info(s"Set sv_res path to '${cfg.cfg.resourcesConfig.svResUri}'")
      val textResourceRepo = context.system.actorOf(TextResourceRepository.props(cfg.cfg.resourcesConfig.svResUri, new RealActorSystemProxy(context.system), new RealHttpProxy()(context.system)))

      //--- domain

      val entryRepo = context.system.actorOf(ElasticsearchRepository.props("sv_blg", "entry", entryRmFormat))
      context.system.actorOf(EntryViewBuilder.props(entryRepo), EntryViewBuilder.Name)
      val entryView = context.system.actorOf(EntryView.props(entryRepo,  textResourceRepo, new RealActorSystemProxy(context.system)), EntryView.Name)
      val entryAggregate = context.system.actorOf(EntryAggregate.props(entryView, textResourceRepo, permsChecker), EntryAggregate.Name)

      val aggregates = HttpRoute.Aggregates(entryAggregate)

      //--- server

      logger.info(s"Set server host to '${cfg.cfg.serverConfig.host}'")
      logger.info(s"Set server port to '${cfg.cfg.serverConfig.port}'")
      logger.info(s"Set tls keystore path to '${cfg.cfg.serverConfig.keystore.file}'")

      implicit val serverMat = ActorMaterializer.create(context.system)

      val password: Array[Char] = cfg.cfg.serverConfig.keystore.password.toCharArray
      val ks: KeyStore = KeyStore.getInstance("PKCS12")
      val storeFile = new File(cfg.cfg.serverConfig.keystore.file)
      if (Files.exists(storeFile.toPath) && Files.isReadable(storeFile.toPath)) {
        try {
          val keystore: InputStream = new FileInputStream(storeFile)
          ks.load(keystore, password)
          val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
          keyManagerFactory.init(ks, password)

          val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
          tmf.init(ks)

          val sslContext: SSLContext = SSLContext.getInstance("TLS")
          sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
          val https: HttpsConnectionContext = ConnectionContext.https(sslContext)

          pipe ( Http(context.system).bindAndHandle(HttpRoute(aggregates).routes, cfg.cfg.serverConfig.host, cfg.cfg.serverConfig.port, connectionContext = https) ) to self
        } catch {
          case e: Throwable =>
            logger.fatal(s"Unable to create http context because '${e.getMessage}'")
            context.system.terminate()
            stop
        }
      } else {
        logger.fatal("Unable to read keystore file")
        context.system.terminate()
        stop
      }

      goto(WaitServerBinding) using WaitingServerBinding()

    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("Configure_StateTimeout")
      logger.fatal("Config repository does not respond with expected timeout")
      context.system.terminate()
      stop
  }

  /** The results of the server binding are expected. If the binding is successful, microservice is considered to be
    * successfully launched. Otherwise, it is a fatal error. The lack of a response from the server is also a fatal
    * error. */
  when(WaitServerBinding, 30 second) {
    case Event(ServerBinding(_), _) =>
      implicit val logQualifier = LogEntryQualifier("WaitServerBinding_ServerBinding")
      logger.info("Server bind was successful")
      logger.info("Microservice was successfully started")
      stop

    case Event(Status.Failure(reason), _) =>
      implicit val logQualifier = LogEntryQualifier("WaitServerBinding_Failure")
      logger.fatal(s"Server binding failed. Reason - $reason")
      context.system.terminate()
      stop

    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("WaitServerBinding_StateTimeout")
      logger.fatal("Server binding procedure does not completed with expected timeout")
      context.system.terminate()
      stop
  }

  initialize()
}