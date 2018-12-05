package ru.serbis.svc.ddd

import akka.actor.{ActorLogging, ActorRef, PoisonPill, Props, ReceiveTimeout}
import akka.persistence._
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * Marker trait for something that is an event generated as the result of a command
  */
trait EntityEvent extends Serializable with DatamodelWriter {
  /**
    * Gets the string identifier of the entity this event is for, for tagging purposes
    */
  def entityType: String
}

/**
  * Companion to the PersistentEntity abstract class
  */
object PersistentEntity {

  /** Request to get the current state from an entity actor */
  case object GetState

  /** Request to mark an entity instance as deleted*/
  case object MarkAsDeleted
}

/**
  * Base class for the Event Sourced entities to extend from
  */
abstract class PersistentEntity[FO <: EntityFieldsObject[String, FO]: ClassTag](id:String)
  extends PersistentActor with StreamLogger {
  import PersistentEntity._

  import concurrent.duration._

  val entityType = getClass.getSimpleName
  var state: FO = initialState
  var eventsSinceLastSnapshot = 0

  //Using this scheduled task as the passivation mechanism
  context.setReceiveTimeout(1 minute)

  //Dynamically setting the persistence id as a combo of
  //entity type and the id of the entity instance
  override def persistenceId = s"$entityType-$id"

  //Recovery combines the standard handling plus the custom handling
  def receiveRecover = customRecover orElse standardRecover

  /**
    * Standard entity recovery logic that all entities will have
    */
  def standardRecover: Receive = {

    //For any entity event, just call handleEvent
    case ev: EntityEvent =>
      implicit val logQualifier = LogEntryQualifier("standardRecover_EntityEvent")

      logger.debug(s"Recovering persisted event: '$ev'")
      handleEvent(ev)
      eventsSinceLastSnapshot += 1

    case SnapshotOffer(meta, snapshot: FO) =>
      implicit val logQualifier = LogEntryQualifier("standardRecover_SnapshotOffer")

      logger.debug(s"Recovering entity with a snapshot: '$snapshot'")
      state = snapshot

    case RecoveryCompleted =>
      implicit val logQualifier = LogEntryQualifier("standardRecover_RecoveryCompleted")

      logger.debug(s"Recovery completed for '$entityType' entity with id '$id'")
  }

  /**
    * Optional custom recovery message handling that a subclass can provide if necessary
    */
  def customRecover: Receive = PartialFunction.empty

  //Command handling combines standard handling plus custom handling
  def receiveCommand = standardCommandHandling orElse additionalCommandHandling

  /**
    * Standard command handling functionality where common logic for all entity types lives
    */
  def standardCommandHandling:Receive = {

    //Have been idle too long, time to start passivation process
    case ReceiveTimeout =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_ReceiveTimeout")

      logger.debug(s"$entityType entity with id '$id' is being passivated due to inactivity")
      context stop self

    //Don't allow actions on deleted entities or a non-create request
    //when in the initial state
    case any if !isAcceptingCommand(any) =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_any0")

      logger.warning(s"Not allowing action '$any' on a deleted entity or an entity in the initial state with id '$id'")
      sender() ! stateResponse()

    //Standard request to get the current state of the entity instance
    case GetState =>
      sender ! stateResponse()

    //Standard handling logic for a request to mark the entity instance  as deleted
    case MarkAsDeleted =>
      //Only if a delete event is defined do we perform the delete.  This
      //allows some entities to not support deletion
      newDeleteEvent match {
        case None =>
          implicit val logQualifier = LogEntryQualifier("standardCommandHandling_MarkAsDeleted")

          logger.debug(s"The entity type '$entityType' does not support deletion, ignoring delete request")
          sender ! stateResponse()

        case Some(event) =>
          persist(event)(handleEventAndRespond())
      }

    case s:SaveSnapshotSuccess =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_SaveSnapshotSuccess")

      logger.debug(s"Successfully saved a new snapshot for entity '$entityType' and id '$id'")

    case f:SaveSnapshotFailure =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_SaveSnapshotFailure")

      logger.error(s"Failed to save a snapshot for entity '$entityType' and id '$id', reason was ${f.cause}")

    case DeleteMessagesSuccess(sn) =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_DeleteMessagesSuccess")

      logger.debug("Successfully cleanup entity persistent journal. Entity stopped")
      self ! PoisonPill

    case DeleteMessagesFailure(e, s) =>
      implicit val logQualifier = LogEntryQualifier("standardCommandHandling_DeleteMessagesFailure")

      logger.info(s"Failure cleanup entity persistent journal. Reason us \n $e")
  }

  /**
    * Determines if the actor can accept the supplied command.  Can't
    * be deleted and if we are in initialState then it can
    * only be the create message
    * @param cmd The command to check
    * @return a Boolean indicating if we can handle the command
    */
  def isAcceptingCommand(cmd:Any) =
    !state.deleted && !(state == initialState && !isCreateMessage(cmd)) ||
      (cmd.isInstanceOf[DeleteMessagesFailure] || cmd.isInstanceOf[DeleteMessagesSuccess])

  /**
    * Implement in the subclass to provide the command handling logic that is
    * specific to this entity class
    */
  def additionalCommandHandling:Receive

  /**
    * Returns an optional delete event message to use when
    * a request to delete happens.  Returns None by default
    * indicating that no delete is supported
    * @return an Option for EntityEvent indicating what event to log for a delete
    */
  def newDeleteEvent: Option[EntityEvent] = None

  /**
    * Returns true if the message is the initial create message
    * which is the only command allowed when in the initial state
    * @param cmd The command to check
    * @return a Boolean indicating if this is the create request
    */
  def isCreateMessage(cmd:Any):Boolean

  /**
    * Returns the initial state of the fields object representing the state for this
    * entity instance.  This will be the initial state before the very first persist call
    * and also the initial state before the recovery process kicks in
    * @return an instance of FO which is the fields object for this entity
    */
  def initialState:FO

  /**
    * Returns the result to send back to the sender when
    * a request to get the current entity state happens
    * @param respectDeleted A boolean that if true means a deleted
    * entity will return en EmptyResult
    * @return a ServiceResult for FO
    */
  def stateResponse(respectDeleted: Boolean = true) = {
    //If we have not persisted this entity yet, then EmptyResult
    if (state == initialState) Failure(FailureType.IncorrectCommand, ErrorMessage("Entity does not exist"))

    //If respecting deleted and it's marked deleted, EmptyResult
    else if (respectDeleted && state.deleted) Failure(FailureType.IncorrectCommand, ErrorMessage("Entity was deleted"))

    //Otherwise, return it as a FullResult
    else FullResult(state)
  }

  /**
    * Implement in a subclass to provide the logic to update the internal state
    * based on receiving an event.  This can be either in recovery or
    * after persisting
    */
  def handleEvent(event:EntityEvent): ServiceResult[Any]

  def customEventRespond(result: ServiceResult[Any], orig: ActorRef): Unit

  /**
    * Handles an event (via handleEvent) and the responds with the current state
    */
  def handleEventAndRespond(orig: ActorRef = ActorRef.noSender)(event:EntityEvent):Unit = {
    val resp = handleEvent(event)
    if (snapshotAfterCount.isDefined){
      eventsSinceLastSnapshot += 1
      maybeSnapshot
    }


    //sender() ! stateResponse(respectDeleted)
    customEventRespond(resp, if (orig == ActorRef.noSender) sender() else orig)
  }

  /**
    * Override in subclass to indicate when to take a snapshot based on eventsSinceLastSnapshot
    * @return an Option that will ne a Some if snapshotting should take place for this entity
    */
  def snapshotAfterCount:Option[Int] = None

  /**
    * Decides if a snapshot is to take place or not after a new event has been processed
    */
  def maybeSnapshot:Unit = {
    implicit val logQualifier = LogEntryQualifier("maybeSnapshot")

    snapshotAfterCount.
      filter(i => eventsSinceLastSnapshot  >= i).
      foreach{ i =>
        logger.debug(s"Taking snapshot because event count '$eventsSinceLastSnapshot' is > snapshot event limit of '$i'")
        saveSnapshot(state)
        eventsSinceLastSnapshot = 0
      }
  }
}

/**
  * Abstract class to represent an Aggregate Actor that sits in front of entities and delegates requests to them
  */
abstract class Aggregate[FO <: EntityFieldsObject[String, FO], E <: PersistentEntity[FO] : ClassTag] extends CommonActor {

  val viewRef: ActorRef

  /**
    * Looks up or creates a new child entity for the id supplied
    * @param id The id of the entity to get
    * @return an ActorRef
    */
  def lookupOrCreateChild(id:String): ActorRef = {
    implicit val logQualifier = LogEntryQualifier("lookupOrCreateChild")


    val name = entityActorName(id)

    if (testMode) {
      val r = Await.result(context.system.actorSelection(s"akka://TestSystem/user/$name").resolveOne(5 second), 5 second)
      r.asInstanceOf[ActorRef]
    } else {
      context.child(name).getOrElse {
        logger.debug(s"Creating new '$entityName' actor to handle a request for id '$id'")
        context.actorOf(entityProps(id), name)
      }
    }

  }

  /**
    * Looks up the entity child for the supplied id and then
    * forwards the supplied message to it
    * @param id The id to get the child for
    * @param msg The message to forward
    */
  def forwardCommandToEntity(id: String, msg: Any, sender: ActorRef):Unit = {
    val entity = lookupOrCreateChild(id)
    entity.tell(msg, sender)
  }

  def forwardCommandToView(msg: Any): Unit = {
    viewRef.forward(msg)
  }

  /**
    * Gets the Props needed to create the child entity for this factory
    * @return a Props instance
    */
  def entityProps(id:String):Props

  /**
    * Determine test mode. If this parameter is true, than lookupOrCreateChild
    * func will be lookup standalone actor instead of child actor.
    * @return mode
    */
  def testMode: Boolean

  /**
    * Gets the name of the entity that this factory manages
    * @return a String
    */
  private def entityName = {
    val entityTag = implicitly[ClassTag[E]]
    entityTag.runtimeClass.getSimpleName
  }



  /**
    * Gets the instance specific name to give child entity actors
    * @param id The id of the child
    * @return a String
    */
  private def entityActorName(id:String) = {
    s"${entityName.toLowerCase}-$id"
  }

}

/**
  * Trait to mix into case classes that represent lightweight representations of the fields for
  * an entity modeled as an actor
  */
trait EntityFieldsObject[K, FO] extends Serializable {
  /**
    * Assigns an id to the fields object, returning a new instance
    * @param id The id to assign
    */
  def assignId(id:K):FO
  def id:K
  def deleted:Boolean
  def markDeleted:FO
}
