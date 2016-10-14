/*
 * Copyright © 2014-2016 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package akka.contrib.cluster.ddata

import akka.actor.ActorRef
import akka.cluster.ddata.DistributedData
import akka.contrib.actor.EmptyActor

/**
 * Base behavior for any actor that is to handle replicated state. To be used in conjunction with any of the
 * stack-able actors e.g. BundleExecutionState, BundleInfoState etc. Concrete implementers should ensure
 * that super.receive is called so that the stack-able traits can handle their related events.
 */
abstract class ReplicatingActor extends EmptyActor {
  protected final val replicator: ActorRef =
    DistributedData(context.system).replicator
}
