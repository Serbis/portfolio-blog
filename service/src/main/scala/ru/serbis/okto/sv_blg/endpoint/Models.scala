package ru.serbis.okto.sv_blg.endpoint

object Models {

  /** Unified wrapper for all endpoint access results */
  case class EndpointResult[T](code: Int, value: T)

  /** Entry representation at updating stage
    *
    * @param locale entry locale
    * @param title entry title
    * @param aText entry text before cut line
    * @param bText entry text after cut line
    */
  case class UpdatedEntry(locale: String, title: Option[String], aText: Option[String], bText: Option[String])
}
