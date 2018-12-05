package ru.serbis.okto.sv_blg.proxy.files

import java.nio.file.{Files, LinkOption, OpenOption, Path}
import java.nio.file.attribute.FileAttribute

/** Proxy for java.nio.Files. This system is used to create end-to-end testing of file I / O operations at the unit test
  * level. The principles of this proxy in the following. This trait has two implementations - test and real. Real
  * implementation duplicates the corresponding calls from the Files object. The test implementation is used for testing
  * to intercept io operations and works as follows. The test implementation constructor has the reference of some actor that
  * will intercept requests, usually TestProbe. When calling any target method, the code of this method makes the ask call
  * to this actor by passing the action case class that defines the given method and its parameters. The interceptor actor
  * checks the received message for correctness, and then responds with a result that should return the target method. At
  * the same time, the interceptor can respond with a special message Throw, upon receipt of which the proxy initiates an
  * throw of the exception specified in the body message. All that remains to be done in practice is to transfer the
  * necessary implementation of the proxy to the actor who is going to use the file io operations. */
trait FilesProxy {
  def deleteIfExists(path: Path): Boolean
  def createFile(path: Path, attrs: FileAttribute[_]*): Path
  def write(path: Path, bytes: Array[Byte], options: OpenOption*): Path
  def exists(path: Path, options: LinkOption*): Boolean
  def readAllBytes(path: Path): Array[Byte]
}
