/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.actor

import akka.actor.Actor
import akka.actor.Actor.emptyBehavior

/**
 * An empty actor is just that - it does nothing, but can be useful for those
 * actors where you wish to apply stack-able traits and thus use this in place
 * of your Actor extending just Actor.
 */
abstract class EmptyActor extends Actor {
  override def receive: Receive =
    emptyBehavior
}
