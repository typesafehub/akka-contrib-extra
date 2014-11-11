/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.actor.Address

/**
 * Make internal `ClusterReadView` cluster information public.
 */
class FullClusterView(cluster: Cluster) {
  private val clusterView: ClusterReadView = cluster.readView

  def state: CurrentClusterState = clusterView.state

  def self: Member = clusterView.self

  def selfAddress = clusterView.selfAddress

  /**
   * Returns true if this cluster instance has be shutdown.
   */
  def isTerminated: Boolean = clusterView.isTerminated

  /**
   * Current cluster members, sorted by address.
   */
  def members: immutable.SortedSet[Member] = clusterView.members

  /**
   * Members that has been detected as unreachable.
   */
  def unreachableMembers: Set[Member] = clusterView.unreachableMembers

  /**
   * Member status for this node (akka.cluster.MemberStatus).
   */
  def status: MemberStatus = clusterView.status

  /**
   * Is this node the leader?
   */
  def isLeader: Boolean = clusterView.isLeader

  /**
   * Get the address of the current leader.
   */
  def leader: Option[Address] = clusterView.leader

  /**
   * Does the cluster consist of only one member?
   */
  def isSingletonCluster: Boolean = clusterView.isSingletonCluster

  /**
   * Returns true if the node is not unreachable and not `Down`
   * and not `Removed`.
   */
  def isAvailable: Boolean = clusterView.isAvailable

  /**
   * Current cluster metrics.
   */
  def clusterMetrics: Set[NodeMetrics] = clusterView.clusterMetrics

  def observersGroupedByUnreachable: Map[UniqueAddress, Set[UniqueAddress]] =
    clusterView.reachability.observersGroupedByUnreachable

  def allUnreachableFrom(observer: UniqueAddress): Set[UniqueAddress] =
    clusterView.reachability.allUnreachableFrom(observer)

}
