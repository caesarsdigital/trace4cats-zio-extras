package com.caesars.tracing

import _root_.sttp.capabilities.WebSockets
import _root_.sttp.capabilities.zio.ZioStreams
import _root_.sttp.client3.SttpBackend
import zio.*

package object sttp {
  type ZioSttpCapabilities = ZioStreams & WebSockets
  type HttpClient = SttpBackend[Task, ZioSttpCapabilities]
}
