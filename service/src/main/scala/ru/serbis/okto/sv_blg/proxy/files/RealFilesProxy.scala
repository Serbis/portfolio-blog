package ru.serbis.okto.sv_blg.proxy.files
import java.nio.file._
import java.nio.file.attribute.FileAttribute

/** Production implementation of the proxy for java.nio.Files. For details see inherited trait */
class RealFilesProxy extends FilesProxy {
  override def deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

  override def createFile(path: Path, attrs: FileAttribute[_]*): Path = Files.createFile(path, attrs: _*)

  override def write(path: Path, bytes: Array[Byte], options: OpenOption*): Path = Files.write(path, bytes, options: _*)

  override def exists(path: Path, options: LinkOption*): Boolean = Files.exists(path, options: _*)

  override def readAllBytes(path: Path): Array[Byte] = Files.readAllBytes(path)
}
