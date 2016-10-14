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

  def noop[A](a: A) = ()

  /**
   * Discern what has changed in the data and call back as appropriate.
   */
  def changed[A](
    oldData: Option[ORMultiMap[A]],
    newData: Option[ORMultiMap[A]],
    bindingAdded: (String, A) => Unit,
    bindingRemoved: (String, A) => Unit,
    bindingChanged: (String, A, A) => Unit,
    distance: (A, A) => Option[Int] = (_: A, _: A) => None): Unit =
    (oldData, newData) match {
      case (Some(oldData: ORMultiMap[A]), Some(newData: ORMultiMap[A])) =>
        def bindings(data: ORMultiMap[A]) =
          data.entries.flatMap { case (k, vs) => vs.map(k -> _) }(breakOut): Set[(String, A)]
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
          .flatMap(bs => (Set.empty[(String, (A, A))] /: bs.toList.sortBy(_._2._2)) {
            case (acc, (k, ((v1, v2), _))) =>
              if (acc.forall { case (_, (vv1, vv2)) => vv1 != v1 && vv2 != v2 }) acc + (k -> (v1, v2)) else acc
          })
        for ((k, v) <- oldBindings.diff(newBindings) -- changedBindings.map { case (k, (v, _)) => k -> v })
          bindingRemoved(k, v)
        for ((k, v) <- newBindings.diff(oldBindings) -- changedBindings.map { case (k, (_, v)) => k -> v })
          bindingAdded(k, v)
        for ((k, (v1, v2)) <- changedBindings)
          bindingChanged(k, v1, v2)

      case (None, Some(newData: ORMultiMap[A])) =>
        for {
          (key, newBindings) <- newData.entries
          element <- newBindings
        } bindingAdded(key, element)

      case (Some(oldData: ORMultiMap[A]), None) =>
        for {
          (key, oldBindings) <- oldData.entries
          element <- oldBindings
        } bindingRemoved(key, element)

      case _ => // We don't care about (None, None)
    }

  /**
   * Return bindings for a given key.
   */
  def collectBindings[A](data: Option[ORMultiMap[A]], key: String): Set[A] =
    getOrEmpty(data).get(key).getOrElse(Set.empty)

  /**
   * Upwarp the given optional `ORMultiMap` or return an empty one.
   */
  def getOrEmpty[A](data: Option[ORMultiMap[A]]): ORMultiMap[A] =
    data.getOrElse(ORMultiMap.empty[A])
}
