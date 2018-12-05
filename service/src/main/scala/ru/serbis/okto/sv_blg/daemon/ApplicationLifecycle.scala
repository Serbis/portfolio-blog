package ru.serbis.okto.sv_blg.daemon

trait ApplicationLifecycle {
  def init(args: Array[String]): Unit
  def start(): Unit
  def stop(): Unit
}
