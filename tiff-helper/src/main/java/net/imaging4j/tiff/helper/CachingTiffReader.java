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
import io.scif.SCIFIO;
import io.scif.codec.CodecOptions;
import io.scif.common.DataTools;
import io.scif.common.Region;
import io.scif.formats.tiff.*;
import io.scif.io.RandomAccessInputStream;
import io.scif.util.FormatTools;
import org.scijava.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.*;

/**
 * Extension of SCIFIO TiffRaster with some additions:
 * caching tiles,
 * synchronizing on the input stream (<tt>in</tt>),
 * ability to detect empty tiles (transparency);
 * checking whether the file is really TIFF.
 */
public final class CachingTiffReader {
    private static final long DEFAULT_MAX_CACHING_MEMORY = Math.max(0, Long.getLong(
        "net.imaging4j.tiff.helper.defaultMaxCachingMemory", 64 * 1048576L));

    private final File file;
    private final SCIFIO scifio;
    private final RandomAccessInputStream randomAccessInputStream;
    private final RandomAccessFile randomAccessFile;
    private final TiffParserWithOptimizedReadingIFDArray parser;
    private final IFDList ifdList;
    private final boolean[] ifdCaching;
    private final byte transparencyFiller;
    private final long maxCachingMemory;

    private final Map<TileIndex, Tile> tileMap = new HashMap<TileIndex, Tile>();
    private final Queue<Tile> tileCache = new LinkedList<Tile>();
    private volatile long currentCacheMemory = 0;
    private final Object tileCacheLock = new Object();
    private final Object fileLock = new Object();

    public CachingTiffReader(Context context, File file)
        throws IOException, FormatException
    {
        this(context, file, (byte) 0xFF, DEFAULT_MAX_CACHING_MEMORY);
    }

    public CachingTiffReader(Context context, File file, byte transparencyFiller)
        throws IOException, FormatException
    {
        this(context, file, transparencyFiller, DEFAULT_MAX_CACHING_MEMORY);
    }

    public CachingTiffReader(
        Context context,
        File file,
        byte transparencyFiller,
        long maxCachingMemory)
        throws IOException, FormatException
    {
        if (maxCachingMemory < 0) {
            throw new IllegalArgumentException("Negative maxCachingMemory");
        }
        long t1 = System.nanoTime();
        this.file = file;
        this.scifio = new SCIFIO(context);
        this.randomAccessFile = new RandomAccessFile(file, "r");
        // We will use this RandomAccessFile for reading large data block (pixel data)
        this.randomAccessInputStream = TiffTools.newSimpleRandomAccessInputStream(context, file);
        long t2 = System.nanoTime();
        this.parser = new TiffParserWithOptimizedReadingIFDArray(context, randomAccessInputStream);
        if (parser.checkHeader() == null) {
            throw new FormatException("Invalid TIFF file: " + file);
        }
        long t3 = System.nanoTime();
        this.ifdList = parser.getIFDs();
        long t4 = System.nanoTime();
        TiffTools.debug(2, "%s instantiating (%s%s): "
                + "%.3f ms (%.3f opening files + %.3f creating parser + %.3f reading IFDs)%n",
            getClass().getSimpleName(),
            randomAccessInputStream.isLittleEndian() ? "little-endian" : "big-endian",
            parser.isBigTiff() ? ", Big-TIFF" : "",
            (t4 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6);
        this.ifdCaching = new boolean[ifdList.size()];
        Arrays.fill(ifdCaching, true);
        this.transparencyFiller = transparencyFiller;
        this.maxCachingMemory = maxCachingMemory;
    }

    public File getFile() {
        return file;
    }

    public SCIFIO getScifio() {
        return scifio;
    }

    public TiffParser getParser() {
        return parser;
    }

    public byte getTransparencyFiller() {
        return transparencyFiller;
    }

    public long getMaxCachingMemory() {
        return maxCachingMemory;
    }

    public List<IFD> getIFDs() throws IOException {
        return Collections.unmodifiableList(ifdList);
    }

    public IFD getIFDByIndex(int ifdIndex) {
        return ifdList.get(ifdIndex);
    }

    public int getIFDCount() {
        return ifdList.size();
    }

    public boolean isIFDCaching(int ifdIndex) {
        return ifdCaching[ifdIndex];
    }

    public void setIfdCaching(int ifdIndex, boolean cachingEnabled) {
        this.ifdCaching[ifdIndex] = cachingEnabled;
    }

    public void setAllIfdCaching(boolean cachingEnabled) {
        Arrays.fill(ifdCaching, cachingEnabled);
    }

    // Note that x+width and y+height may be outside the image: it does not lead to exception,
    // though may lead to unfilled (zero) values.
    // However, must be x>=0 and y>=0.
    public byte[] readSamples(
        byte[] result,
        final boolean[] opaque,
        final int ifdIndex,
        final int x,
        final int y,
        final int width,
        final int height)
        throws FormatException, IOException
    {
        final IFD ifd = ifdList.get(ifdIndex);
        final int bandCount = ifd.getSamplesPerPixel();
        if (result == null) {
            final int pixelType = ifd.getPixelType();
            final int bytesPerBand = Math.max(1, FormatTools.getBytesPerPixel(pixelType));
            // - "max" to be on the safe side
            final int maxPixelCount = Integer.MAX_VALUE / bytesPerBand / bandCount;
            if ((long) width * (long) height > maxPixelCount) {
                throw new IllegalArgumentException("Too large rectangle "
                    + x + ".." + (x + width) + "x" + y + ".." + (y + height));
            }
            result = new byte[width * height * bytesPerBand * bandCount];
        }
        TiffTools.checkIfdSizes(ifd, width, height);
        final int planarConfiguration = ifd.getPlanarConfiguration();
        final int bytesPerSample = ifd.getBytesPerSample()[0];
        final int effectiveChannels = planarConfiguration == 2 ? 1 : bandCount;
        if (opaque != null && result.length != opaque.length * bytesPerSample * bandCount) {
            throw new IllegalArgumentException("Result and opaque lengths mismatch: " + result.length
                + " != " + opaque.length + "*" + bytesPerSample + "*" + bandCount);
        }
        if (x < 0 || y < 0
            || (long) x + (long) width > Integer.MAX_VALUE || (long) y + (long) height > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Requested area is out of 0..2^31 ranges");
        }
        final int toX = x + width;
        final int toY = y + height;

        long tileWidth = ifd.getTileWidth();
        long tileHeight = ifd.getTileLength();
        if (tileHeight <= 0) {
            tileHeight = height;
        }
        if (tileWidth > Integer.MAX_VALUE || tileHeight > Integer.MAX_VALUE
            || tileWidth * tileHeight > Integer.MAX_VALUE
            || tileWidth * tileHeight * (long) bytesPerSample > Integer.MAX_VALUE
            || tileWidth * tileHeight * (long) bytesPerSample * (long) bandCount > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Too large TIFF tiles: "
                + "tileWidth * tileHeight * bytes per sample * samples per pixel = "
                + tileWidth + " * " + tileHeight + " * " + bytesPerSample + " * " + bandCount + " >= 2^31"
                + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
        final long numTileRows = ifd.getTilesPerColumn();
        final long numTileCols = ifd.getTilesPerRow();
        final long effectiveNumTileRows = planarConfiguration == 2 ? numTileRows * bandCount : numTileRows;

        final Region imageBounds = new Region(x, y, width, height);
        final int tileRowSize = (int) tileWidth * bytesPerSample;
        final int outputRowSize = width * bytesPerSample;
        final int tileSize = (int) (tileRowSize * tileHeight);
        final int planeSizeInBytes = width * height * bytesPerSample;

        Region tileBounds = new Region(0, 0, (int) tileWidth, (int) tileHeight);
        if (opaque != null) {
            Arrays.fill(opaque, false);
        }

        for (int row = 0; row < effectiveNumTileRows; row++) {
            for (int column = 0; column < numTileCols; column++) {
                tileBounds.x = column * (int) tileWidth;
                tileBounds.y = row * (int) tileHeight;

                if (planarConfiguration == 2) {
                    tileBounds.y = (int) ((row % numTileRows) * tileHeight);
                }
                if (!imageBounds.intersects(tileBounds)) {
                    continue;
                }
                final Tile tile = getTile(new TileIndex(ifdIndex, row, column));
                final byte[] tileBuffer = tile.readData();
                final boolean tileContainsData = tileBuffer != null;

                final int tileX = Math.max(tileBounds.x, x);
                final int tileY = Math.max(tileBounds.y, y);
                final int xRemainder = tileX % (int) tileWidth;
                final int yRemainder = tileY % (int) tileHeight;
                assert xRemainder < tileWidth;
                assert yRemainder < tileHeight;

                final int partWidth = (int) Math.min(toX - tileX, tileWidth - xRemainder);
                assert partWidth > 0 : "partWidth=" + partWidth;
                final int partHeight = (int) Math.min(toY - tileY, tileHeight - yRemainder);
                assert partHeight > 0 : "partHeight=" + partHeight;

                final int partWidthInBytes = bytesPerSample * partWidth;

                for (int q = 0; q < effectiveChannels; q++) {
                    int srcOfs = q * tileSize + xRemainder * bytesPerSample + yRemainder * tileRowSize;
                    int destOfs = q * planeSizeInBytes + (tileX - x) * bytesPerSample + (tileY - y) * outputRowSize;
                    if (planarConfiguration == 2) {
                        destOfs += (planeSizeInBytes * (row / numTileRows));
                    }
                    final boolean needToFillOpaque = opaque != null && tileContainsData && q == 0 && row < numTileRows;
                    // - opaque contains only 1 element per pixel, not samplesPerPixel elements
                    int opaqueOfs = destOfs / bytesPerSample;

                    // Note: original TiffParser contains a bug here.
                    // It tries to optimize the following loop with the following check:
                    //     if (rowLen == outputRowLen && overlapX == 0 && overlapY == 0) {
                    //         System.arraycopy(cachedTileBuffer, src, buf, dest, copy * theight);
                    //     } ...
                    // (rowLen is our tileRowSize, outputRowLen is our outputRowSize).
                    // But it is incorrect: it is possible that rowLen == outputRowLen even for a part of tile,
                    // when width == tileWidth, but x % tileWidth != 0.
                    for (int tileRow = 0; tileRow < partHeight; tileRow++) {
                        if (tileBuffer == null) {
                            Arrays.fill(result, destOfs, destOfs + partWidthInBytes, transparencyFiller);
                        } else {
                            System.arraycopy(tileBuffer, srcOfs, result, destOfs, partWidthInBytes);
                        }
                        if (needToFillOpaque) {
                            Arrays.fill(opaque, opaqueOfs, opaqueOfs + partWidth, true);
                        }
                        srcOfs += tileRowSize;
                        destOfs += outputRowSize;
                        opaqueOfs += width;
                    }
                }
            }
        }
        return result;
    }

    public Object readSamplesToJavaArray(
        final int ifdIndex,
        final int x,
        final int y,
        final int width,
        final int height,
        Class<?> requiredElementType,
        Integer requiredBandCount)
        throws FormatException, IOException
    {
        return readSamplesToJavaArray(null, ifdIndex, x, y, width, height, requiredElementType, requiredBandCount);
    }

    public Object readSamplesToJavaArray(
        final boolean[] opaque,
        final int ifdIndex,
        final int x,
        final int y,
        final int width,
        final int height,
        Class<?> requiredElementType,
        Integer requiredBandCount)
        throws FormatException, IOException
    {
        final IFD ifd = getIFDByIndex(ifdIndex);
        if (requiredBandCount != null && ifd.getSamplesPerPixel() != requiredBandCount) {
            throw new FormatException("Number of bands mismatch: expected " + requiredBandCount
                + " bands (samples per pixel), but IFD image #" + ifdIndex + " contains " + ifd.getSamplesPerPixel()
                + " bands; image description " + ifd.get(IFD.IMAGE_DESCRIPTION));
        }
        if (requiredElementType != null && TiffTools.javaElementType(ifd.getPixelType()) != requiredElementType) {
            throw new FormatException("Element type mismatch: expected " + requiredElementType
                + "[] elements, but some IFD image contains " + TiffTools.javaElementType(ifd.getPixelType())
                + "[] elements; image description " + ifd.get(IFD.IMAGE_DESCRIPTION));
        }
        final int pixelType = ifd.getPixelType();
        byte[] bytes = readSamples(null, opaque, ifdIndex, x, y, width, height);
        bytes = TiffTools.interleaveSamples(bytes, width * height, ifd);
        return DataTools.makeDataArray(
            bytes,
            Math.max(1, FormatTools.getBytesPerPixel(pixelType)),
            // - "max" to be on the safe side
            FormatTools.isFloatingPoint(pixelType),
            ifd.isLittleEndian());
    }

    public Tile getTile(TileIndex tileIndex) {
        synchronized (tileCacheLock) {
            Tile tile = tileMap.get(tileIndex);
            if (tile == null) {
                tile = new Tile(tileIndex);
                tileMap.put(tileIndex, tile);
            }
            return tile;
            // So, we will store (without ability to remove) all Tile objects in the global cache tileMap.
            // It is not a problem, because Tile is a very lightweight object.
            // In any case, ifdList already contains comparable amount of data: strip offsets and strip byte counts.
        }
    }

    public void close() throws IOException {
        randomAccessInputStream.close();
        randomAccessFile.close();
    }

    public final class Tile {
        private final TileIndex tileIndex;

        private final Object onlyThisTileLock = new Object();
        private volatile boolean noData = false;
        // -  volatile to be on the safe side, if in future someone will try to read this outside synchronization
        private Reference<byte[]> cachedData = null;
        // - we use SoftReference to be on the safe side in addition to our own memory control
        private long cachedDataSize;

        private Tile(TileIndex tileIndex) {
            if (tileIndex == null) {
                throw new NullPointerException("Null tileIndex");
            }
            this.tileIndex = tileIndex;
        }

        public byte[] readData() throws FormatException, IOException {
            synchronized (onlyThisTileLock) {
                if (noData) {
                    TiffTools.debug(2, "CACHED empty tile: %s%n", tileIndex);
                    return null;
                }
                final byte[] cachedData = cachedData();
                if (cachedData != null) {
                    TiffTools.debug(2, "CACHED tile: %s%n", tileIndex);
                    return cachedData;
                }
                final IFD ifd = ifdList.get(tileIndex.ifdIndex);
                final boolean littleEndian = ifd.isLittleEndian();
                final TiffCompression compression = ifd.getCompression();
                final CodecOptions codecOptions =
                    compression == TiffCompression.JPEG_2000 || compression == TiffCompression.JPEG_2000_LOSSY ?
                        compression.getCompressionCodecOptions(ifd, parser.getCodecOptions()) :
                        compression.getCompressionCodecOptions(ifd);
                codecOptions.interleaved = true;
                codecOptions.littleEndian = littleEndian;

                final byte[] jpegTable = (byte[]) ifd.getIFDValue(IFD.JPEG_TABLES);

                final long tileWidth = ifd.getTileWidth();
                final long tileHeight = ifd.getTileLength();
                final int samplesPerPixel = ifd.getSamplesPerPixel();
                final int planarConfiguration = ifd.getPlanarConfiguration();

                final long numTileCols = ifd.getTilesPerRow();

                final int bytesPerSample = ifd.getBytesPerSample()[0];
                final int effectiveChannels = planarConfiguration == 2 ? 1 : samplesPerPixel;

                final long[] stripByteCounts = ifd.getStripByteCounts();
                final long[] rowsPerStrip = ifd.getRowsPerStrip();

                final int offsetIndex = (int) (tileIndex.row * numTileCols + tileIndex.column);
                final int countIndex = parser.equalStrips ? 0 : offsetIndex;
                if (stripByteCounts[countIndex] == (rowsPerStrip[0] * tileWidth) && bytesPerSample > 1) {
                    stripByteCounts[countIndex] *= bytesPerSample;
                }

                byte[] tileBytes;
                final long stripOffset;
                final long nStrips;
                synchronized (fileLock) {
                    if (ifd.getOnDemandStripOffsets() != null) {
                        // Parallel operations with OnDemandLongArray also must be synchronized
                        OnDemandLongArray stripOffsets = ifd.getOnDemandStripOffsets();
                        stripOffset = stripOffsets.get(offsetIndex);
                        nStrips = stripOffsets.size();
                    } else {
                        long[] stripOffsets = ifd.getStripOffsets();
                        stripOffset = stripOffsets[offsetIndex];
                        nStrips = stripOffsets.length;
                    }

                    if (stripByteCounts[countIndex] == 0 || stripOffset >= randomAccessFile.length()) {
                        TiffTools.debug(2, "Empty tile (stored in cache): %s%n", tileIndex);
                        noData = true;
                        return null;
                    }
                    tileBytes = new byte[(int) stripByteCounts[countIndex]];
                    randomAccessFile.seek(stripOffset);
                    randomAccessFile.read(tileBytes);
                    // It is the main part where this class reads data from file, and we prefer
                    // to use more simple RandomAccessFile instead of RandomAccessInputStream
                    // (which is based on NIOFileHandle by default) to avoid possible memory problems
                    // in possible future implementations. Here we do not need the main feature of
                    // SCIFIO RandomAccessInputStream - byte order (we read simple byte array).
                }
                final int size = (int) (tileWidth * tileHeight * bytesPerSample * effectiveChannels);
                final byte[] result = new byte[size];

                codecOptions.maxBytes = Math.max(size, tileBytes.length);
                codecOptions.ycbcr =
                    ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR &&
                        ifd.getIFDIntValue(IFD.Y_CB_CR_SUB_SAMPLING) == 1 && parser.ycbcrCorrection;

                if (jpegTable != null) {
                    byte[] q = new byte[jpegTable.length + tileBytes.length - 4];
                    System.arraycopy(jpegTable, 0, q, 0, jpegTable.length - 2);
                    System.arraycopy(tileBytes, 2, q, jpegTable.length - 2, tileBytes.length - 2);
                    tileBytes = compression.decompress(scifio.codec(), q, codecOptions);
                } else {
                    tileBytes = compression.decompress(scifio.codec(), tileBytes, codecOptions);
                }
                scifio.tiff().undifference(tileBytes, ifd);
                TiffParserWithOptimizedReadingIFDArray.unpackBytes(result, 0, tileBytes, ifd);

                if (planarConfiguration == 2 && !ifd.isTiled() && ifd.getSamplesPerPixel() > 1) {
                    int channel = (int) (tileIndex.row % nStrips);
                    if (channel < ifd.getBytesPerSample().length) {
                        int realBytes = ifd.getBytesPerSample()[channel];
                        if (realBytes != bytesPerSample) {
                            // re-pack pixels to account for differing bits per sample
                            int[] samples = new int[result.length / bytesPerSample];
                            for (int i = 0; i < samples.length; i++) {
                                samples[i] =
                                    DataTools.bytesToInt(result, i * realBytes, realBytes, littleEndian);
                            }
                            for (int i = 0; i < samples.length; i++) {
                                DataTools.unpackBytes(
                                    samples[i], result, i * bytesPerSample, bytesPerSample, littleEndian);
                            }
                        }
                    }
                }
                boolean fillerOnly = true;
                for (byte v : result) {
                    if (v != transparencyFiller) {
                        fillerOnly = false;
                        break;
                    }
                }
                if (fillerOnly) {
                    TiffTools.debug(2, "Transparent tile detected and recognized as empty (stored in cache): %s%n",
                        tileIndex);
                    noData = true;
                    return null;
                }
                saveCache(result);
                return result;
            }
        }

        private byte[] cachedData() {
            synchronized (tileCacheLock) {
                if (cachedData == null) {
                    return null;
                }
                byte[] data = cachedData.get();
                if (data == null) {
                    TiffTools.debug(1,
                        "CACHED tile is freed by garbage collector due to global insufficiency of memory: %s%n",
                        tileIndex);
                }
                return data;
            }
        }

        private void saveCache(byte[] data) {
            if (ifdCaching[tileIndex.ifdIndex] && maxCachingMemory > 0) {
                synchronized (tileCacheLock) {
                    this.cachedData = new SoftReference<byte[]>(data);
                    this.cachedDataSize = data.length;
                    currentCacheMemory += data.length;
                    tileCache.add(this);
                    TiffTools.debug(2, "Tile (stored in cache): %s%n", tileIndex);
                    while (currentCacheMemory > maxCachingMemory) {
                        Tile tile = tileCache.remove();
                        assert tile != null;
                        currentCacheMemory -= tile.cachedDataSize;
                        tile.cachedData = null;
                        Runtime runtime = Runtime.getRuntime();
                        TiffTools.debug(2, "Tile removed from cache "
                                + "(cache memory limit %.1f MB exceeded, used memory %.1f MB): %s%n",
                            maxCachingMemory / 1048576.0,
                            (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0,
                            tile.tileIndex
                        );
                    }
                }
            }
        }
    }

    public static final class TileIndex {
        private final int ifdIndex;
        private final int row;
        private final int column;

        public TileIndex(int ifdIndex, int row, int column) {
            if (ifdIndex < 0) {
                throw new IllegalArgumentException("Negative ifdIndex = " + ifdIndex);
            }
            if (row < 0) {
                throw new IllegalArgumentException("Negative row = " + row);
            }
            if (column < 0) {
                throw new IllegalArgumentException("Negative row = " + column);
            }
            this.ifdIndex = ifdIndex;
            this.row = row;
            this.column = column;
        }

        public int getIfdIndex() {
            return ifdIndex;
        }

        public int getRow() {
            return row;
        }

        public int getColumn() {
            return column;
        }

        @Override
        public String toString() {
            return "IFD #" + ifdIndex + ", row " + row + ", column " + column;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TileIndex tileIndex = (TileIndex) o;
            return column == tileIndex.column && ifdIndex == tileIndex.ifdIndex && row == tileIndex.row;

        }

        @Override
        public int hashCode() {
            int result = ifdIndex;
            result = 31 * result + row;
            result = 31 * result + column;
            return result;
        }
    }
}
