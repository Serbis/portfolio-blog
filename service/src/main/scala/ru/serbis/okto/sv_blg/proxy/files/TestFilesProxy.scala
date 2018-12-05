package ru.serbis.okto.sv_blg.proxy.files

import java.nio.file.attribute.FileAttribute
import java.nio.file.{Files, LinkOption, OpenOption, Path}
import akka.pattern.ask
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.util.ByteString

import scala.concurrent.Await

/** Test implementation of the proxy for java.nio.Files. For details see inherited trait */
object TestFilesProxy {
  object Actions {
    case class DeleteIfExists(path: Path)
    case class CreateFile(path: Path, attrs: FileAttribute[_]*)
    case class Write(path: Path, bytes: ByteString, options: OpenOption*)
    case class Exists(path: Path, options: LinkOption*)
    case class ReadAllBytes(path: Path)
  }

  object Predicts {
    case class Throw(ex: Throwable)
  }
}

class TestFilesProxy(tpRef: ActorRef) extends FilesProxy {
  import TestFilesProxy._
  override def deleteIfExists(path: Path): Boolean = {
    Await.result(tpRef.ask(Actions.DeleteIfExists(path))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: Boolean => v
    }
  }

  override def createFile(path: Path, attrs: FileAttribute[_]*): Path = {
    Await.result(tpRef.ask(Actions.CreateFile(path, attrs: _*))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: Path => v
    }
  }

  override def write(path: Path, bytes: Array[Byte], options: OpenOption*): Path = {
    Await.result(tpRef.ask(Actions.Write(path, ByteString(bytes), options: _*))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: Path => v
    }
  }

  override def exists(path: Path, options: LinkOption*): Boolean = {
    Await.result(tpRef.ask(Actions.Exists(path, options: _*))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: Boolean => v
    }
  }

  override def readAllBytes(path: Path): Array[Byte] = {
    Await.result(tpRef.ask(Actions.ReadAllBytes(path))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: Array[Byte] => v
    }
  }
}
