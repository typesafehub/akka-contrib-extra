/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.cluster.ddata.{ ORSet, ReplicatedData }

object ORSetStateSpec {
  case class Data(v: String) extends ReplicatedData {
    override type T = this.type
    override def merge(that: Data.this.type): Data.this.type =
      that
  }
}

class ORSetStateSpec extends StateSpec {
  import ORSetStateSpec._

  "ORSetState" should {

    "signal that an element is added with no previous data" in {
      val entry = Some(ORSet.empty[Data] + Data("bar"))
      def added(value: Data): Unit =
        value shouldBe Data("bar")
      def removed(value: Data): Unit =
        fail("Shouldn't be removed")
      ORSetState.changed(None, entry, added, removed)
    }

    "signal that an element is added with some previous empty data" in {
      val entry = Some(ORSet.empty[Data] + Data("bar"))
      def added(value: Data): Unit =
        value shouldBe Data("bar")
      def removed(value: Data): Unit =
        fail("Shouldn't be removed")
      ORSetState.changed(Some(ORSet.empty[Data]), entry, added, removed)
    }

    "not signal that an element is added with the same previous data" in {
      val entry = Some(ORSet.empty[Data] + Data("bar"))
      def added(value: Data): Unit =
        fail("Shouldn't be added")
      def removed(value: Data): Unit =
        fail("Shouldn't be removed")
      ORSetState.changed(entry, entry, added, removed)
    }

    "signal that an element is removed with some previous data" in {
      val entry = Some(ORSet.empty[Data] + Data("bar"))
      def added(value: Data): Unit =
        fail("Shouldn't be added")
      def removed(value: Data): Unit =
        value shouldBe Data("bar")
      ORSetState.changed(entry, Some(ORSet.empty[Data]), added, removed)
    }
  }
}
