package com.caesars.tracing.sttp

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.*

type ZioSttpCapabilities = ZioStreams & WebSockets
type HttpClient = SttpBackend[Task, ZioSttpCapabilities]
