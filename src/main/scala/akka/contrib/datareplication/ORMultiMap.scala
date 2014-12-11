package akka.contrib.datareplication

import akka.cluster.{ UniqueAddress, Cluster }

object ORMultiMap {

  val empty: ORMultiMap =
    new ORMultiMap(ORMap.empty)

  def apply(map: ORMap): ORMultiMap =
    new ORMultiMap(map)
}

/**
 * An immutable multi-map implementation for akka-data-replication.
 */
@SerialVersionUID(1L)
class ORMultiMap private (private[akka] val map: ORMap)
    extends ReplicatedData with RemovedNodePruning with Serializable {

  override type T = ORMultiMap

  override def merge(that: T): T =
    new ORMultiMap(map.merge(that.map))

  def entries: Map[String, ORSet] =
    map.entries.asInstanceOf[Map[String, ORSet]]

  def get(key: String): Option[ORSet] =
    map.get(key).asInstanceOf[Option[ORSet]]

  def addBinding(key: String, element: Any)(implicit cluster: Cluster): ORMultiMap = {
    val values = updateOrInit(key, _ + element, ORSet.empty + element)
    ORMultiMap(map + (key -> values))
  }

  def removeBinding(key: String, element: Any)(implicit cluster: Cluster): ORMultiMap = {
    val values = updateOrInit(key, _ - element, ORSet.empty)
    if (values.value.nonEmpty)
      ORMultiMap(map + (key -> values))
    else
      ORMultiMap(map - key)
  }

  def removeBindings(key: String, p: Any => Boolean)(implicit cluster: Cluster): ORMultiMap = {
    val values = updateOrInit(key, values => (values /: values.value)(_ - _), ORSet.empty)
    if (values.value.nonEmpty)
      ORMultiMap(map + (key -> values))
    else
      ORMultiMap(map - key)
  }

  private def updateOrInit(key: String, update: ORSet => ORSet, init: => ORSet): ORSet =
    map.get(key).asInstanceOf[Option[ORSet]] match {
      case Some(values) => update(values)
      case None         => init
    }

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    map.needPruningFrom(removedNode)

  override def pruningCleanup(removedNode: UniqueAddress): T =
    new ORMultiMap(map.pruningCleanup(removedNode))

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): T =
    new ORMultiMap(map.prune(removedNode, collapseInto))
}
