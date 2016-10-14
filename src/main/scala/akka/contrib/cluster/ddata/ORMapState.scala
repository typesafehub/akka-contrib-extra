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
  def changed[A <: ReplicatedData](
    oldData: Option[ORMap[A]],
    newData: Option[ORMap[A]],
    valueAdded: (String, A) => Unit,
    valueRemoved: (String, A) => Unit,
    valueChanged: (String, A, A) => Unit): Unit =
    (oldData, newData) match {
      case (Some(oldMaps: ORMap[A]), Some(newMaps: ORMap[A])) =>
        for ((key, oldValue) <- oldMaps.entries)
          newMaps.entries.get(key) match {
            case Some(newValue) if newValue != oldValue => valueChanged(key, oldValue, newValue)
            case Some(newValue)                         =>
            case None                                   => valueRemoved(key, oldValue)
          }
        for ((key, newValue) <- newMaps.entries if !oldMaps.entries.contains(key))
          valueAdded(key, newValue)

      case (None, Some(newMaps: ORMap[A])) =>
        for ((key, newValue) <- newMaps.entries)
          valueAdded(key, newValue)

      case (Some(oldMaps: ORMap[A]), None) =>
        for ((key, oldValue) <- oldMaps.entries)
          valueRemoved(key, oldValue)

      case _ => // We don't care about (None, None)
    }

  def getValue[A <: ReplicatedData](data: Option[ORMap[A]], key: String): Option[A] =
    get(data).get(key)

  def get[A <: ReplicatedData](data: Option[ORMap[A]]): ORMap[A] =
    data.getOrElse(ORMap.empty[A])
}
