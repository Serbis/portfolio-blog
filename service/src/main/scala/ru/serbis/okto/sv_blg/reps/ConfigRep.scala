package ru.serbis.okto.sv_blg.reps

import java.io.File
import java.nio.file.Files
import akka.actor.{Actor, Props, Stash}
import com.typesafe.config.{Config, ConfigFactory}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.{Logger, StreamLogger}
import akka.pattern.pipe
import collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

/** The repository provides thread-safe operations on the service.conf file. This file uses the configuration format of
  * the lightbend config library. The configuration is downloaded one-time in asynchronous mode on the first request */
object ConfigRep {

  /** @param confPath path to the configuration file */
  def props(confPath: String) = Props(new ConfigRep(confPath))

  case object Commands {

    /** Request for all configurations params. Configuration reads from the disk only once. Fot all subsequent requests,
      * cached copy will be returned. Respond with RuntimeConfig message */
    case object Get
  }

  case object Responses {
    import Definitions.ServiceConfig

    /** Full configuration container
      *
      * @param cfg configuration object
      */
    case class RuntimeConfig(cfg: ServiceConfig)
  }

  case object Definitions {

    /** Top level config object */
    case class ServiceConfig(
      logConfig: LogConfig,
      serverConfig: ServerConfig,
      securityConfig: SecurityConfig,
      resourcesConfig: ResourcesConfig
    )

    /** Logger config definition */
    case class LogConfig(
      level: Logger.LogLevels.LogLevel,
      keys: Seq[String],
      path: java.nio.file.Path,
      truncate: Boolean
    )

    /** Server config definition */
    case class ServerConfig(
      host: String,
      port: Int,
      keystore: KeystoreConfig
    )

    /** Keystore config definition */
    case class KeystoreConfig(
      file: String,
      password: String
    )

    /** Security config definition */
    case class SecurityConfig(
      svSecUri: String,
    )

    /** Resources config definition */
    case class ResourcesConfig(
      svResUri: String
    )
  }

  case object Internals {

    /** This message is intended for asynchronous loading of the configuration. See the comment to the function withConf.
      *
      * @param config loaded configuration
      */
    case class ConfigLoaded(config: Config)
  }
}

class ConfigRep(confPath: String) extends Actor with StreamLogger with Stash {
  import ConfigRep.Commands._
  import ConfigRep.Internals._
  import ConfigRep.Definitions._
  import ConfigRep.Responses._

  setLogSourceName(s"ConfigRep*${self.path.name}")
  setLogKeys(Seq("ConfigRep"))

  implicit val logQualifier = LogEntryQualifier("static")

  /** Current configuration instance */
  var config: Option[Config] = None

  var serviceConfig: Option[ServiceConfig] = None

  logger.info("ConfigRep actor is initialized")

  /** see the message description */
  override def receive = {
    case Get =>
      if (serviceConfig.isDefined) {
        sender() ! RuntimeConfig(serviceConfig.get)
      } else {
        withConf {
          serviceConfig = readConfig()
          sender() ! RuntimeConfig(readConfig().get)
        }
      }

    /** see the message description */
    case ConfigLoaded(conf) =>
      config = Some(conf)
      unstashAll()
  }

  /** Collect all configuration fields to its program representation. If some of the fields does not exist or not readable,
    * it's replace to the default velue. If default value does not provided, function return None */
  def readConfig() = {

    //------------- service.log --------------

    val level = Try {
      config.get.getString("service.log.level") match {
        case "DEBUG" => Logger.LogLevels.Debug
        case "INFO" => Logger.LogLevels.Info
        case "WARNING" => Logger.LogLevels.Warning
        case "ERROR" => Logger.LogLevels.Error
        case "FATAL" => Logger.LogLevels.Fatal
        case _ =>
          logger.warning("The log level parameter 'logLevel' is incorrectly specified, the default value of 'INFO' is returned")
          Logger.LogLevels.Info
      }
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> log -> level. The default value of INFO is returned. Error reason: ${r.getMessage}")
        Logger.LogLevels.Info
    }

    val keys = Try {
      val keys = config.get.getStringList("service.log.keys")
      keys.asScala
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> log -> keys. The default value of empty seq is returned. Error reason: ${r.getMessage}")
        Seq.empty
    }

    val file = Try {
      val path = new File(config.get.getString("service.log.file")).toPath
      path
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> log -> file. The default value of '/tmp/node.log' is returned. Error reason: ${r.getMessage}")
        new File("/tmp/node.log").toPath
    }

    val truncate = Try {
      config.get.getBoolean("service.log.fileTruncate")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> log -> fileTruncate. The default value of 'true' is returned. Error reason: ${r.getMessage}")
        true
    }

    //------------- service.server --------------

    val host = Try {
      config.get.getString("service.server.host")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> server -> host. The default value of '127.0.0.1' is returned. Error reason: ${r.getMessage}")
        "127.0.0.1"
    }

    val port = Try {
      config.get.getInt("service.server.port")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> server -> port. The default value of '5001' is returned. Error reason: ${r.getMessage}")
        5001
    }

    //------------- service.server.keystore --------------

    val keystoreFile = Try {
      config.get.getString("service.server.keystore.file")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service -> server -> keystore -> file. The default value of '/etc/pki/tls' is returned. Error reason: ${r.getMessage}")
        "/etc/pki/tls"
    }

    val keystorePassword = Try {
      config.get.getString("service.server.keystore.password")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> server -> port. The default value of '5001' is returned. Error reason: ${r.getMessage}")
        "123456"
    }

    //------------- service.security --------------

    val securitySvSecUri = Try {
      config.get.getString("service.security.svSecUri")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> security -> svSecUri. The default value of 'https://localhost:5100' is returned. Error reason: ${r.getMessage}")
        "https://localhost:5100"
    }

    //------------- service.security --------------

    val resourcesSvResUri = Try {
      config.get.getString("service.resources.svResUri")
    } recover {
      case r =>
        logger.warning(s"Error reading configuration parameter service-> resources -> svResUri. The default value of 'https://localhost:5101' is returned. Error reason: ${r.getMessage}")
        "https://localhost:5101"
    }


    val keystoreConfig = KeystoreConfig(keystoreFile.get, keystorePassword.get)

    Some(ServiceConfig(
      LogConfig(level.get, keys.get, file.get, truncate.get),
      ServerConfig(host.get, port.get, keystoreConfig),
      SecurityConfig(securitySvSecUri.get),
      ResourcesConfig(resourcesSvResUri.get)
    ))

  }

  /** This function executes the body of the message, pre-checking the availability of the configuration object. If
    * the configuration was not previously loaded from the disk, the function performs a stash of the current message
    * and load the configuration from the disk via the future. When the future finishes, it sends to the self,
    * ConfigurationLoaded message, in which contains the created configuration object is assigned and the messages is
    * unstashed.
    *
    * @param f message handler body
    */
  def withConf(f: => Unit) = {
    if (config.isEmpty) {
      stash()
      pipe (Future {
        val file = new File(confPath)
        if (!Files.exists(file.toPath))
          logger.warning(s"The configuration '$confPath' was not found in the path")
        ConfigLoaded(ConfigFactory.parseFile(file)) }) to self
    } else {
      f
    }
  }
}
