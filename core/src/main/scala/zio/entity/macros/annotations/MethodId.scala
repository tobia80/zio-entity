package zio.entity.macros.annotations

/** Used to annotate method with a unique id (every method must have a different id) in order to allow evolution.
  * If not defined, the name of the method will be used. Renaming method will break the api during a deployment
  * @param id unique id
  */
class MethodId(id: Int) extends scala.annotation.StaticAnnotation
