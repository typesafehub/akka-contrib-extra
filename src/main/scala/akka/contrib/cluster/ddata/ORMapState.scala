/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.{ ORMap, ReplicatedData }

/**
 * Useful helper methods for ORMap - particularly around managing change.
 */
object ORMapState {
  def changed[A, B <: ReplicatedData](
    oldData: Option[ORMap[A, B]],
    newData: Option[ORMap[A, B]],
    valueAdded: (A, B) => Unit,
    valueRemoved: (A, B) => Unit,
    valueChanged: (A, B, B) => Unit): Unit =
    (oldData, newData) match {
      case (Some(oldMaps: ORMap[A, B]), Some(newMaps: ORMap[A, B])) =>
        for ((key, oldValue) <- oldMaps.entries)
          newMaps.entries.get(key) match {
            case Some(newValue) if newValue != oldValue => valueChanged(key, oldValue, newValue)
            case Some(_)                                =>
            case None                                   => valueRemoved(key, oldValue)
          }
        for ((key, newValue) <- newMaps.entries if !oldMaps.entries.contains(key))
          valueAdded(key, newValue)

      case (None, Some(newMaps: ORMap[A, B])) =>
        for ((key, newValue) <- newMaps.entries)
          valueAdded(key, newValue)

      case (Some(oldMaps: ORMap[A, B]), None) =>
        for ((key, oldValue) <- oldMaps.entries)
          valueRemoved(key, oldValue)

      case _ => // We don't care about (None, None)
    }

  def getValue[A, B <: ReplicatedData](data: Option[ORMap[A, B]], key: A): Option[B] =
    get(data).get(key)

  def get[A, B <: ReplicatedData](data: Option[ORMap[A, B]]): ORMap[A, B] =
    data.getOrElse(ORMap.empty[A, B])
}
