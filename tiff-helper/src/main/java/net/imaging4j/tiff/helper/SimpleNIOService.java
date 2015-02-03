/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2015 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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