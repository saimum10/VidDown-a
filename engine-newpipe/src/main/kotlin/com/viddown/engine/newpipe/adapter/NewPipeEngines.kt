package com.viddown.engine.newpipe.adapter

import com.viddown.engine.api.StreamMerger
import com.viddown.engine.direct.OkHttpTransport
import com.viddown.engine.newpipe.NewPipeEngine

/**
 * The one place that wires the production NewPipe engine, so the app module never needs a NewPipe class: it asks for an engine and
 * gets a [NewPipeEngine]. One OkHttp client serves both the extractor's requests and the file transfer.
 */
object NewPipeEngines {
    /** [merger] puts separate video and audio streams together; without one only single-file qualities are offered. */
    fun create(merger: StreamMerger? = null): NewPipeEngine {
        val client = OkHttpTransport.defaultClient()
        return NewPipeEngine(
            gateway = ExtractorGateway(OkHttpDownloader(client)),
            transport = OkHttpTransport(client),
            merger = merger,
        )
    }
}
