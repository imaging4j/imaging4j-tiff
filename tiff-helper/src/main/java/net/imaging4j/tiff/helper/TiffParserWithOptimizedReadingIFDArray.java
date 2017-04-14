/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2017 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.FormatException;
import io.scif.codec.BitBuffer;
import io.scif.common.DataTools;
import io.scif.formats.tiff.*;
import io.scif.io.RandomAccessInputStream;
import org.scijava.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

class TiffParserWithOptimizedReadingIFDArray extends TiffParser {
    private static final boolean DEBUG_OPTIMIZATION = false;

    boolean ycbcrCorrection = true;
    // - providing access to private field in TiffParser (both fields are always equal);
    // default value is the same as in that field
    boolean equalStrips = false;
    // - providing access to private field in TiffParser (both fields are always equal);
    // default value is the same as in that field ("true" value does not work correctly with some TIFFs)

    public TiffParserWithOptimizedReadingIFDArray(Context context, RandomAccessInputStream in) {
        super(context, in);
        super.setYCbCrCorrection(ycbcrCorrection);
        super.setAssumeEqualStrips(equalStrips);
    }

    @Override
    public void setYCbCrCorrection(boolean correctionAllowed) {
        super.setYCbCrCorrection(correctionAllowed);
        this.ycbcrCorrection = correctionAllowed;
    }

    @Override
    public void setAssumeEqualStrips(boolean equalStrips) {
        super.setAssumeEqualStrips(equalStrips);
        this.equalStrips = equalStrips;
    }

    @Override
    public TiffIFDEntry readTiffIFDEntry() throws IOException {
        final TiffIFDEntry result = super.readTiffIFDEntry();
        TiffTools.debug(2, "%s reading IFD entry: %s - %s%n",
            getClass().getSimpleName(), result, TiffTools.ifdTagName(result.getTag()));
        return result;
    }

    @Override
    public IFD getIFD(long offset) throws IOException {
        long t1 = System.nanoTime();
        final IFD result = super.getIFD(offset);
        long t2 = System.nanoTime();
        TiffTools.debug(2, "%s reading IFD at offset %d: %.3f ms%n",
            getClass().getSimpleName(), offset, (t2 - t1) * 1e-6);
        return result;
    }

    @Override
    public Object getIFDValue(TiffIFDEntry entry) throws IOException {
        long t1 = TiffTools.DEBUG_LEVEL >= 2 ? System.nanoTime() : 0;
        final RandomAccessInputStream in = getStream();
        final int count = entry.getValueCount();
        final Object result;
        if (count == 1) {
            // special case in TiffParser.getIFDValue
            result = super.getIFDValue(entry);
        } else {
            switch (entry.getType()) {
                case LONG: {
                    if (!prepareReadingIFD(entry)) {
                        return null;
                    }
                    final long[] longs = new long[count];
                    final ByteBuffer buffer = ByteBuffer.allocateDirect(count * 4);
                    buffer.order(in.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    in.read(buffer);
                    buffer.rewind();
                    getLongs(buffer.asIntBuffer(), longs);
                    result = longs;
                    debugOptimization(longs, entry);
                    break;
                }
                case LONG8: {
                    if (!prepareReadingIFD(entry)) {
                        return null;
                    }
                    final long[] longs = new long[count];
                    final ByteBuffer buffer = ByteBuffer.allocateDirect(count * 8);
                    buffer.order(in.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    in.read(buffer);
                    buffer.rewind();
                    buffer.asLongBuffer().get(longs);
                    result = longs;
                    debugOptimization(longs, entry);
                    break;
                }
                default: {
                    result = super.getIFDValue(entry);
                    break;
                }
            }
        }
        long t2 = TiffTools.DEBUG_LEVEL >= 2 ? System.nanoTime() : 0;
        if (t2 - t1 > 1000000 || count > 1000) {
            TiffTools.debug(2, "Reading %s in %.3f ms, %.1f ns/element%n",
                entry, (t2 - t1) * 1e-6, (double) (t2 - t1) / (double) entry.getValueCount());
        }
        return result;
    }

    private boolean prepareReadingIFD(TiffIFDEntry entry) throws IOException {
        final long offset = entry.getValueOffset();
        final RandomAccessInputStream in = getStream();
        if (offset >= in.length()) {
            return false;
        }

        if (offset != in.getFilePointer()) {
            in.seek(offset);
        }
        return true;
    }

    private void debugOptimization(long[] optimized, TiffIFDEntry entry) throws IOException {
        if (DEBUG_OPTIMIZATION) {
            final Object ifdValue = super.getIFDValue(entry);
            if (!(ifdValue instanceof long[])) {
                throw new AssertionError("Unexpected type of getIFDValue result");
            }
            long[] longs = (long[]) ifdValue;
            if (longs.length != optimized.length) {
                throw new AssertionError("Invalid length: " + optimized.length + " instead of " + longs.length);
            }
            for (int i = 0; i < longs.length; i++) {
                if (optimized[i] != longs[i]) {
                    throw new AssertionError("Different element #" + i + ": 0x"
                        + Long.toHexString(optimized[i]) + " instead of " + Long.toHexString(longs[i]));
                }
            }
            TiffTools.debug(1, "Testing %s: all O'k%n", entry);
        }
    }

    private static void getLongs(IntBuffer buffer, long[] dest) {
        for (int i = 0; i < dest.length; i++) {
            dest[i] = buffer.get();
        }
    }

    /**
     * A copy of the same private method in TiffParser class.
     */
    static void unpackBytes(
        final byte[] samples, final int startIndex,
        final byte[] bytes, final IFD ifd) throws FormatException
    {
        final boolean planar = ifd.getPlanarConfiguration() == 2;

        final TiffCompression compression = ifd.getCompression();
        PhotoInterp photoInterp = ifd.getPhotometricInterpretation();
        if (compression == TiffCompression.JPEG) {
            photoInterp = PhotoInterp.RGB;
        }

        final int[] bitsPerSample = ifd.getBitsPerSample();
        int nChannels = bitsPerSample.length;

        int sampleCount = (int) (((long) 8 * bytes.length) / bitsPerSample[0]);
        if (photoInterp == PhotoInterp.Y_CB_CR) {
            sampleCount *= 3;
        }
        if (planar) {
            nChannels = 1;
        } else {
            sampleCount /= nChannels;
        }

        final long imageWidth = ifd.getImageWidth();
        final long imageHeight = ifd.getImageLength();

        final int bps0 = bitsPerSample[0];
        final int numBytes = ifd.getBytesPerSample()[0];
        final int nSamples = samples.length / (nChannels * numBytes);

        final boolean noDiv8 = bps0 % 8 != 0;
        final boolean bps8 = bps0 == 8;
        final boolean bps16 = bps0 == 16;

        final boolean littleEndian = ifd.isLittleEndian();

        final BitBuffer bb = new BitBuffer(bytes);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there is
        // only one channel, the source byte buffer's size is less than or equal to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if ((bps8 || bps16) && bytes.length <= samples.length && nChannels == 1 &&
            photoInterp != PhotoInterp.WHITE_IS_ZERO &&
            photoInterp != PhotoInterp.CMYK && photoInterp != PhotoInterp.Y_CB_CR)
        {
            System.arraycopy(bytes, 0, samples, 0, bytes.length);
            return;
        }

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterp == PhotoInterp.CMYK) {
            maxValue = Integer.MAX_VALUE;
        }

        int skipBits = (int) (8 - ((imageWidth * bps0 * nChannels) % 8));
        if (skipBits == 8 ||
            (bytes.length * 8 < bps0 * (nChannels * imageWidth + imageHeight)))
        {
            skipBits = 0;
        }

        // set up YCbCr-specific values
        float lumaRed = PhotoInterp.LUMA_RED;
        float lumaGreen = PhotoInterp.LUMA_GREEN;
        float lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIFDIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[] {0, 0, 0, 0, 0, 0};
        }
        final int[] subsampling = ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING);
        final TiffRational[] coefficients =
            (TiffRational[]) ifd.getIFDValue(IFD.Y_CB_CR_COEFFICIENTS);
        if (coefficients != null) {
            lumaRed = coefficients[0].floatValue();
            lumaGreen = coefficients[1].floatValue();
            lumaBlue = coefficients[2].floatValue();
        }
        final int subX = subsampling == null ? 2 : subsampling[0];
        final int subY = subsampling == null ? 2 : subsampling[1];
        final int block = subX * subY;
        final int nTiles = (int) (imageWidth / subX);

        // unpack pixels
        for (int sample = 0; sample < sampleCount; sample++) {
            final int ndx = startIndex + sample;
            if (ndx >= nSamples) {
                break;
            }

            for (int channel = 0; channel < nChannels; channel++) {
                final int index = numBytes * (sample * nChannels + channel);
                final int outputIndex = (channel * nSamples + ndx) * numBytes;

                // unpack non-YCbCr samples
                if (photoInterp != PhotoInterp.Y_CB_CR) {
                    long value = 0;

                    if (noDiv8) {
                        // bits per sample is not a multiple of 8

                        if ((channel == 0 && photoInterp == PhotoInterp.RGB_PALETTE) ||
                            (photoInterp != PhotoInterp.CFA_ARRAY && photoInterp != PhotoInterp.RGB_PALETTE))
                        {
                            value = bb.getBits(bps0) & 0xffff;
                            if ((ndx % imageWidth) == imageWidth - 1) {
                                bb.skipBits(skipBits);
                            }
                        }
                    } else {
                        value = DataTools.bytesToLong(bytes, index, numBytes, littleEndian);
                    }

                    if (photoInterp == PhotoInterp.WHITE_IS_ZERO ||
                        photoInterp == PhotoInterp.CMYK)
                    {
                        value = maxValue - value;
                    }

                    if (outputIndex + numBytes <= samples.length) {
                        DataTools.unpackBytes(value, samples, outputIndex, numBytes,
                            littleEndian);
                    }
                } else {
                    // unpack YCbCr samples; these need special handling, as each of
                    // the RGB components depends upon two or more of the YCbCr components
                    if (channel == nChannels - 1) {
                        final int lumaIndex = sample + (2 * (sample / block));
                        final int chromaIndex = (sample / block) * (block + 2) + block;

                        if (chromaIndex + 1 >= bytes.length) {
                            break;
                        }

                        final int tile = ndx / block;
                        final int pixel = ndx % block;
                        final long r = subY * (tile / nTiles) + (pixel / subX);
                        final long c = subX * (tile % nTiles) + (pixel % subX);

                        final int idx = (int) (r * imageWidth + c);

                        if (idx < nSamples) {
                            final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                            final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                            final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                            final int red = (int) (cr * (2 - 2 * lumaRed) + y);
                            final int blue = (int) (cb * (2 - 2 * lumaBlue) + y);
                            final int green =
                                (int) ((y - lumaBlue * blue - lumaRed * red) / lumaGreen);

                            samples[idx] = (byte) (red & 0xff);
                            samples[nSamples + idx] = (byte) (green & 0xff);
                            samples[2 * nSamples + idx] = (byte) (blue & 0xff);
                        }
                    }
                }
            }
        }
    }
}
