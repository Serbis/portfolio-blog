package ru.serbis.svc.ddd

import akka.persistence.journal.{EventAdapter, EventSeq, Tagged}

import scalapb.GeneratedMessage

/**
  * Trait for a class that can write it's state into a protobuf message
  */
trait DatamodelWriter{

  /**
    * Convert this domain model object's state into a protobuf message
    * @return the protobuf message for this object
    */
  def toDatamodel: GeneratedMessage
}

/**
  * Trait for a class that can read a protobuf message and produce a corresponding
  * domain model object
  */
trait DatamodelReader{

  /**
    * Returns a partial function for converting into the domain model
    * @return the partial function for handling the conversion
    */
  def fromDatamodel:PartialFunction[GeneratedMessage,AnyRef]
}

/**
  * Generic adapter class that will convert to a from the journal via protobuf
  */
class ProtobufDatamodelAdapter extends EventAdapter {
  override def manifest(event:Any) = event.getClass.getName

  override def toJournal(event:Any) = event match {
    case ev:EntityEvent with DatamodelWriter =>
      val message = ev.toDatamodel

      //Add tags for the entity type and the event class name
      val eventType = ev.getClass.getName.toLowerCase().split("\\$").last
      Tagged(message, Set(ev.entityType, eventType))

    //case wr:DatamodelWriter =>
    //  wr.toDatamodel
    case _ => throw new RuntimeException(s"Protobuf adapter can't write adapt type: $event")
  }

  override def fromJournal(event:Any, manifest:String) = {
    event match{
      case m: GeneratedMessage =>
        val reader = Class.forName(manifest + "$").getField("MODULE$").get(null).asInstanceOf[DatamodelReader]
        reader.
          fromDatamodel.
          lift(m).
          map(EventSeq.single).
          getOrElse(throw readException(event))

      case _ => throw readException(event)

    }
  }

  private def readException(event:Any) = new RuntimeException(s"Protobuf adapter can't read adapt for type: $event")
}