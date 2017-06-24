/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.ORMultiMap

import scala.collection.breakOut

/**
 * Useful helper methods for ORMultiMap - particularly around managing change.
 */
object ORMultiMapState {

  def noop[A](a: A): Unit = ()

  /**
   * Discern what has changed in the data and call back as appropriate.
   */
  def changed[A, B](
    oldData: Option[ORMultiMap[A, B]],
    newData: Option[ORMultiMap[A, B]],
    bindingAdded: (A, B) => Unit,
    bindingRemoved: (A, B) => Unit,
    bindingChanged: (A, B, B) => Unit,
    distance: (B, B) => Option[Int] = (_: B, _: B) => None): Unit =
    (oldData, newData) match {
      case (Some(oldData: ORMultiMap[A, B]), Some(newData: ORMultiMap[A, B])) =>
        def bindings(data: ORMultiMap[A, B]) =
          data.entries.flatMap { case (k, vs) => vs.map(k -> _) }(breakOut): Set[(A, B)]
        def combinations[AC, BC](as: Set[AC], bs: Set[BC]) =
          for (a <- as; b <- bs) yield a -> b
        val oldBindings = bindings(oldData)
        val newBindings = bindings(newData)
        val equalBindings = oldBindings.intersect(newBindings)
        val changedBindings = combinations(oldBindings -- equalBindings, newBindings -- equalBindings)
          .collect {
            case ((k1, v1), (k2, v2)) if k1 == k2 && distance(v1, v2).isDefined =>
              k1 -> ((v1, v2) -> math.abs(distance(v1, v2).get))
          }
          .groupBy(_._1)
          .values
          .flatMap(bs => (Set.empty[(A, (B, B))] /: bs.toList.sortBy(_._2._2)) {
            case (acc, (k, ((v1, v2), _))) =>
              if (acc.forall { case (_, (vv1, vv2)) => vv1 != v1 && vv2 != v2 }) acc + (k -> (v1, v2)) else acc
          })
        for ((k, v) <- oldBindings.diff(newBindings) -- changedBindings.map { case (k, (v, _)) => k -> v })
          bindingRemoved(k, v)
        for ((k, v) <- newBindings.diff(oldBindings) -- changedBindings.map { case (k, (_, v)) => k -> v })
          bindingAdded(k, v)
        for ((k, (v1, v2)) <- changedBindings)
          bindingChanged(k, v1, v2)

      case (None, Some(newData: ORMultiMap[A, B])) =>
        for {
          (key, newBindings) <- newData.entries
          element <- newBindings
        } bindingAdded(key, element)

      case (Some(oldData: ORMultiMap[A, B]), None) =>
        for {
          (key, oldBindings) <- oldData.entries
          element <- oldBindings
        } bindingRemoved(key, element)

      case _ => // We don't care about (None, None)
    }

  /**
   * Return bindings for a given key.
   */
  def collectBindings[A, B](data: Option[ORMultiMap[A, B]], key: A): Set[B] =
    getOrEmpty(data).get(key).getOrElse(Set.empty)

  /**
   * Upwarp the given optional `ORMultiMap` or return an empty one.
   */
  def getOrEmpty[A, B](data: Option[ORMultiMap[A, B]]): ORMultiMap[A, B] =
    data.getOrElse(ORMultiMap.empty[A, B])
}
