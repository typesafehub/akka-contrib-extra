/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib

import com.typesafe.config.{ ConfigFactory, Config }

package object process {

  val testConfig: Config = {
    def testConfig: Config =
      ConfigFactory parseString """|akka {
                                   |  loglevel = debug
                                   |  actor {
                                   |    debug.fsm = false
                                   |    #Commented out for now given: https://github.com/akka/akka/issues/17393
                                   |    #serialize-messages = on
                                   |  }
                                   |
                                   |}""".stripMargin
    ConfigFactory.defaultOverrides() withFallback testConfig withFallback ConfigFactory.load()
  }
}
