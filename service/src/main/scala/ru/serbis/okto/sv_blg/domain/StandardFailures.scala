package ru.serbis.okto.sv_blg.domain

import ru.serbis.svc.ddd.{ErrorMessage, Failure, FailureType}

object StandardFailures {
  val AccessDenied = Failure(FailureType.Validation, ErrorMessage("access_denied", Some("User is not authorized to perform this operation")))
  val SecurityError = Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
  val EntityAlreadyExist = Failure(FailureType.Validation, ErrorMessage("entity_already_created", Some("Unable to create entity because entity already exist")))
  val InitializationFailure = (reason: String) => Failure(FailureType.Internal, ErrorMessage("entity_init_error", Some(s"Unable to initialize entity, reason '$reason'")))
  val EntryUpdateFailure = (reason: String) => Failure(FailureType.Internal, ErrorMessage("entry_update_error", Some(s"Unable to update entity, reason '$reason'")))
  val EntryDeleteFailure = (reason: String) => Failure(FailureType.Internal, ErrorMessage("entry_update_error", Some(s"Unable to delete entity, reason '$reason'")))
  val EntityResourceFetchFailure = (reason: String) => Failure(FailureType.Internal, ErrorMessage("entry_resource_fetch_error", Some(s"Unable to complete operation because '$reason'")))
  val ReadSideRepoTimeout = (reason: String) => Failure(FailureType.Internal, ErrorMessage("read_side_repo_timeout", Some(s"Unable to complete operation because interaction with read side repository completes with failure, reason '$reason'")))
  val TextsCollectorTimeout = (reason: String) => Failure(FailureType.Internal, ErrorMessage("texts_collector_timeout", Some(s"Unable to complete operation because interaction with read texts collector fsm completes with failure, reason '$reason'")))
  val ReadSideRepoError = (reason: String) => Failure(FailureType.Internal, ErrorMessage("read_side_repo_error", Some(s"Unable to complete operation because interaction with read side repository completes with error, reason '$reason'")))
  val ParamsValidationError = (reason: String) => Failure(FailureType.Internal, ErrorMessage("params_validation_error", Some(s"Unable to perform operation because input parameters does not pass validation check, message '$reason'")))



}
