/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.{ ORMap, ReplicatedData }

object ORMapStateSpec {
  case class Data(v: String) extends ReplicatedData {
    override type T = this.type
    override def merge(that: Data.this.type): Data.this.type =
      that
  }
}

class ORMapStateSpec extends StateSpec {

  import ORMapStateSpec._

  "ORMapState" should {

    "signal that an element is added with no previous data" in {
      val entry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar")))
      def added(key: String, value: Data): Unit = {
        key shouldBe "foo"
        value shouldBe Data("bar")
      }
      def removed(key: String, value: Data): Unit = {
        fail("Shouldn't be removed")
      }
      def changed(key: String, oldValue: Data, newValue: Data): Unit = {
        fail("Shouldn't be changed")
      }
      ORMapState.changed(None, entry, added, removed, changed)
    }

    "signal that an element is added with some previous empty data" in {
      val entry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar")))
      def added(key: String, value: Data): Unit = {
        key shouldBe "foo"
        value shouldBe Data("bar")
      }
      def removed(key: String, value: Data): Unit = {
        fail("Shouldn't be removed")
      }
      def changed(key: String, oldValue: Data, newValue: Data): Unit = {
        fail("Shouldn't be changed")
      }
      ORMapState.changed(Some(ORMap.empty[String, Data]), entry, added, removed, changed)
    }

    "signal that an element is removed and then added with some previous changed data" in {
      val oldEntry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar1")))
      val newEntry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar2")))
      def added(key: String, value: Data): Unit = {
        fail("Shouldn't be added")
      }
      def removed(key: String, value: Data): Unit = {
        fail("Shouldn't be removed")
      }
      def changed(key: String, oldValue: Data, newValue: Data): Unit = {
        oldValue shouldBe Data("bar1")
        newValue shouldBe Data("bar2")
      }
      ORMapState.changed(oldEntry, newEntry, added, removed, changed)
    }

    "not signal that an element is added with the same previous data" in {
      val entry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar")))
      def added(key: String, value: Data): Unit = {
        fail("Shouldn't be added")

      }
      def removed(key: String, value: Data): Unit = {
        fail("Shouldn't be removed")
      }
      def changed(key: String, oldValue: Data, newValue: Data): Unit = {
        fail("Shouldn't be changed")
      }
      ORMapState.changed(entry, entry, added, removed, changed)
    }

    "signal that an element is removed with some previous data" in {
      val entry = Some(ORMap.empty[String, Data] + ("foo" -> Data("bar")))
      def added(key: String, value: Data): Unit = {
        fail("Shouldn't be added")
      }
      def removed(key: String, value: Data): Unit = {
        key shouldBe "foo"
        value shouldBe Data("bar")
      }
      def changed(key: String, oldValue: Data, newValue: Data): Unit = {
        fail("Shouldn't be changed")
      }
      ORMapState.changed(entry, Some(ORMap.empty[String, Data]), added, removed, changed)
    }
  }
}
