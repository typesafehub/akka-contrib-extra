/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.{ ORSet, ReplicatedData }

/**
 * Useful helper methods for ORSet - particularly around managing change.
 */
object ORSetState {
  def changed[A <: ReplicatedData](
    oldData: Option[ORSet[A]],
    newData: Option[ORSet[A]],
    valueAdded: A => Unit,
    valueRemoved: A => Unit): Unit =
    (oldData, newData) match {
      case (Some(oldSets: ORSet[A]), Some(newSets: ORSet[A])) =>
        for (oldValue <- oldSets.elements)
          if (!newSets.elements.contains(oldValue))
            valueRemoved(oldValue)
        for ((newValue) <- newSets.elements if !oldSets.elements.contains(newValue))
          valueAdded(newValue)

      case (None, Some(newSets: ORSet[A])) =>
        for (newValue <- newSets.elements)
          valueAdded(newValue)

      case (Some(oldSets: ORSet[A]), None) =>
        for (oldValue <- oldSets.elements)
          valueRemoved(oldValue)

      case _ => // We don't care about (None, None)
    }
}
