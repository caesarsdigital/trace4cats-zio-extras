package com.caesars

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams

package object tracing {
  type ZioSttpCapabilities = ZioStreams & WebSockets
}
