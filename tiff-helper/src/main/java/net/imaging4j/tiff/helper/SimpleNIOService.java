package net.imaging4j.tiff.helper;

import io.scif.io.NIOService;
import org.scijava.service.AbstractService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

class SimpleNIOService extends AbstractService implements NIOService {
    @Override
    public ByteBuffer allocate(
            FileChannel channel,
            FileChannel.MapMode mapMode,
            long bufferStartPosition,
            int newSize)
            throws IOException
    {
        long t1 = System.nanoTime();
        final ByteBuffer buffer = ByteBuffer.allocate(newSize);
        channel.read(buffer, bufferStartPosition);
        long t2 = System.nanoTime();
        TiffTools.debug(3, "%s allocating and reading buffer: %d bytes from %d, %.3f ms%n",
                getClass().getSimpleName(), newSize, bufferStartPosition, (t2 - t1) * 1e-6);
        return buffer;
    }
}