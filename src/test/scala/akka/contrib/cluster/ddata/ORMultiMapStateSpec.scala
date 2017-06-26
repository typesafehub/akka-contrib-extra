/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.ORMultiMap

class ORMultiMapStateSpec extends StateSpec {

  "ORMultiMapState" should {
    "discern a change for where there is no existing binding, but a new element" in {
      val newData = ORMultiMap.empty[String, String].addBinding("a", "1")

      var bindingsAdded = Set.empty[String]
      def added(key: String, value: Any) = bindingsAdded += value.toString
      def removed(key: String, value: Any) = fail("Shouldn't be removed")
      def changed(key: String, oldValue: Any, newValue: Any) = fail("Shouldn't be changed")
      ORMultiMapState.changed(None, Some(newData), added, removed, changed)

      bindingsAdded shouldBe Set("1")
    }

    "discern a change for where there is an existing binding, but a new element" in {
      val oldData = ORMultiMap.empty[String, String].addBinding("a", "1")
      val newData = ORMultiMap.empty[String, String].addBinding("a", "1").addBinding("a", "2")

      var bindingsAdded = Set.empty[String]
      def added(key: String, value: String) = bindingsAdded += value
      def removed(key: String, value: String) = fail("Shouldn't be removed")
      def changed(key: String, oldValue: String, newValue: String) = fail("Shouldn't be changed")
      ORMultiMapState.changed(Some(oldData), Some(newData), added, removed, changed)

      bindingsAdded shouldBe Set("2")
    }

    "do the right thing for a complex change" in {
      val oldData = ORMultiMap.empty[String, Int]
        .addBinding("a", 1)
        .addBinding("a", 2)
        .addBinding("a", 3)
        .addBinding("b", 1)
        .addBinding("b", 2)
        .addBinding("b", 3)
      val newData = ORMultiMap.empty[String, Int]
        .addBinding("a", 1)
        .addBinding("a", 3)
        .addBinding("a", 4)
        .addBinding("a", 5)
        .addBinding("b", 2)
        .addBinding("b", 4)
      var bindingsAdded = Set.empty[(String, Int)]
      var bindingsRemoved = Set.empty[(String, Int)]
      var bindingsChanged = Set.empty[(String, Int, Int)]
      ORMultiMapState.changed(
        Some(oldData),
        Some(newData),
        (k: String, v: Int) => bindingsAdded += k -> v,
        (k: String, v: Int) => bindingsRemoved += k -> v,
        (k: String, v1: Int, v2: Int) => bindingsChanged += ((k, v1, v2)), // Damn auto-tupling!
        (v1: Int, v2: Int) => Some(v1 - v2)
      )
      bindingsAdded shouldBe Set("a" -> 5)
      bindingsRemoved shouldBe Set("b" -> 1)
      bindingsChanged shouldBe Set(("a", 2, 4), ("b", 3, 4))
    }
  }
}
