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

import io.scif.FormatException;
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import io.scif.formats.tiff.TiffSaver;
import io.scif.io.RandomAccessOutputStream;
import org.scijava.Context;

import java.io.File;
import java.io.IOException;

public final class SequentialTiffWriter {
    private final TiffSaver saver;
    private final RandomAccessOutputStream randomAccessOutputStream;

    private volatile int imageWidth = -1;
    private volatile int imageHeight = -1;
    private volatile boolean tiling = false;
    private volatile int tileWidth = -1;
    private volatile int tileHeight = -1;
    private volatile boolean interleaved = true;
    private volatile TiffCompression compression = null;
    private volatile PhotoInterp photometricInterpretation = null;
    private volatile String software;

    public SequentialTiffWriter(Context context, File tiffFile, boolean bigTiff, boolean littleEndian)
        throws IOException
    {
        this.saver = new TiffSaverWith32BitGeometricTags(context, tiffFile.getAbsolutePath());
        this.randomAccessOutputStream = this.saver.getStream();
        this.saver.setBigTiff(bigTiff);
        this.saver.setWritingSequentially(true);
        this.saver.setLittleEndian(littleEndian);
        this.saver.writeHeader();
    }

    public TiffSaver getSaver() {
        return saver;
    }

    public boolean isBigTiff() {
        return saver.isBigTiff();
    }

    public boolean isLittleEndian() {
        return saver.isLittleEndian();
    }

    public int getImageWidth() {
        if (imageWidth < 0) {
            throw new IllegalStateException("Image sizes not set");
        }
        return imageWidth;
    }

    public int getImageHeight() {
        if (imageHeight < 0) {
            throw new IllegalStateException("Image sizes not set");
        }
        return imageHeight;
    }

    public void setImageSizes(int imageWidth, int imageHeight) {
        if (imageWidth < 0) {
            throw new IllegalArgumentException("Negative imageWidth");
        }
        if (imageHeight < 0) {
            throw new IllegalArgumentException("Negative imageHeight");
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public boolean isTiling() {
        return tiling;
    }

    public void setTiling(boolean tiling) {
        this.tiling = tiling;
    }

    public int getTileWidth() {
        if (tileWidth < 0) {
            throw new IllegalStateException("Tile sizes not set");
        }
        return tileWidth;
    }

    public int getTileHeight() {
        if (tileHeight < 0) {
            throw new IllegalStateException("Tile sizes not set");
        }
        return tileHeight;
    }

    public void setTileSizes(int tileWidth, int tileHeight) {
        if (tileWidth < 0) {
            throw new IllegalArgumentException("Negative tile width");
        }
        if (tileHeight < 0) {
            throw new IllegalArgumentException("Negative tile height");
        }
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
    }

    public boolean isInterleaved() {
        return interleaved;
    }

    public void setInterleaved(boolean interleaved) {
        this.interleaved = interleaved;
    }

    public TiffCompression getCompression() {
        return compression;
    }

    public void setCompression(TiffCompression compression) {
        if (compression == null) {
            throw new NullPointerException("Null compression");
        }
        this.compression = compression;
    }

    public PhotoInterp getPhotometricInterpretation() {
        return photometricInterpretation;
    }

    public void setPhotometricInterpretation(PhotoInterp photometricInterpretation) {
        if (photometricInterpretation == null) {
            throw new NullPointerException("Null photometric interpretation");
        }
        this.photometricInterpretation = photometricInterpretation;
    }

    public String getSoftware() {
        return software;
    }

    public void setSoftware(String software) {
        this.software = software;
    }

    public void setCodecOptions(CodecOptions options) {
        options = new CodecOptions(options);
        options.littleEndian = saver.isLittleEndian();
        saver.setCodecOptions(options);
    }

    public void writeSeveralTilesOrStrips(
        final byte[] data, final IFD ifd,
        final int pixelType, int bandCount,
        final int lefTopX, final int leftTopY,
        final int width, final int height,
        final boolean lastTileOrStripInImage,
        final boolean lastImageInTiff) throws FormatException, IOException
    {
        if (compression == null) {
            throw new IllegalStateException("Compression not set");
        }
        ifd.putIFDValue(IFD.IMAGE_WIDTH, (long) getImageWidth());
        ifd.putIFDValue(IFD.IMAGE_LENGTH, (long) getImageHeight());
        if (tiling) {
            ifd.putIFDValue(IFD.TILE_WIDTH, (long) getTileWidth());
            ifd.putIFDValue(IFD.TILE_LENGTH, (long) getTileHeight());
        } else {
            ifd.remove(IFD.TILE_WIDTH);
            ifd.remove(IFD.TILE_LENGTH);
        }
        ifd.putIFDValue(IFD.PLANAR_CONFIGURATION, interleaved ? 1 : 2);
        ifd.putIFDValue(IFD.COMPRESSION, compression.getCode());
        ifd.putIFDValue(IFD.LITTLE_ENDIAN, isLittleEndian());
        if (photometricInterpretation != null) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, photometricInterpretation.getCode());
        }
        if (software != null) {
            ifd.putIFDValue(IFD.SOFTWARE, software);
        }
        final long fp = randomAccessOutputStream.getFilePointer();
        final PhotoInterp requestedPhotoInterp = ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION) ?
            ifd.getPhotometricInterpretation() :
            null;
        saver.writeImage(data, ifd, -1, pixelType, lefTopX, leftTopY, width, height, lastImageInTiff, bandCount, true);
        // - planeIndex = -1 is not used in Writing-Sequentially mode
        if (lastTileOrStripInImage
            && bandCount > 1
            && requestedPhotoInterp == PhotoInterp.Y_CB_CR
            && ifd.getPhotometricInterpretation() == PhotoInterp.RGB)
        {
            switch (ifd.getCompression()) {
                case JPEG:
                case OLD_JPEG: {
                    randomAccessOutputStream.seek(fp);
                    ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, requestedPhotoInterp.getCode());
                    saver.writeIFD(ifd, lastImageInTiff ? 0 : randomAccessOutputStream.length());
                    // - I don't know why, but we need to replace RGB photometric, automatically
                    // set by TiffSaver, with YCbCr, in other case this image is shown incorrectly.
                    // We must do this here, not before writeImage: writeImage automatically sets it to RGB.
                    break;
                }
            }
        }
        if (requestedPhotoInterp != null) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, requestedPhotoInterp.getCode());
            // - restoring photometric, overwritten by TiffSaver
        }
        randomAccessOutputStream.seek(lastTileOrStripInImage ? randomAccessOutputStream.length() : fp);
        // - this stupid SCIFIO class requires this little help to work correctly
    }

    public void close() throws IOException {
        randomAccessOutputStream.close();
    }

}
