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
import io.scif.formats.tiff.IFD;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class TiffReaderTest {
    private static final int MAX_IMAGE_SIZE = 6000;

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReaderTest.class.getName()
                + " some_tiff_file.tif ifdIndex result.png");
            return;
        }
        final File tiffFile = new File(args[0]);
        final int ifdIndex = Integer.parseInt(args[1]);
        final File resultFile = new File(args[2]);

        System.out.printf("Opening %s...%n", tiffFile);
        CachingTiffReader reader = new CachingTiffReader(new SCIFIO().getContext(), tiffFile);
        final IFD ifd = reader.getIFDByIndex(ifdIndex);
        final int width = (int) Math.min(ifd.getImageWidth(), MAX_IMAGE_SIZE);
        final int height = (int) Math.min(ifd.getImageLength(), MAX_IMAGE_SIZE);
        final int bandCount = ifd.getSamplesPerPixel();

        System.out.printf("Reading data %dx%dx%d from IFD #%d/%d %s[%dx%d]:%n%s",
            width, height, bandCount, ifdIndex, reader.getIFDCount(),
            TiffTools.javaElementType(ifd.getPixelType()).getSimpleName(),
            ifd.getImageWidth(),
            ifd.getImageLength(),
            TiffTools.toString(ifd));
        byte[] bytes = (byte[]) reader.readSamplesToJavaArray(
            ifdIndex, 0, 0, width, height, byte.class, null);

//        final TiffParser parser = new TiffParser(new SCIFIO().getContext(), tiffFile.getPath());
//        parser.getSamples(ifd, bytes, 0, 0, width, height);

        System.out.printf("Converting data to BufferedImage...%n");
        final BufferedImage image = bytesToImage(bytes, width, height, bandCount);
        System.out.printf("Saving result image into %s...%n", resultFile);
        if (!ImageIO.write(image, "png", resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        System.out.println("Done");
    }

    private static BufferedImage bytesToImage(byte[] bytes, int width, int height, int bandCount) {
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0, offset = 0; y < height; y++) {
            for (int x = 0; x < width; x++, offset += bandCount) {
                final byte r = bytes[offset];
                final byte g = bytes[bandCount == 1 ? offset : offset + 1];
                final byte b = bytes[bandCount == 1 ? offset : offset + 2];
                final int rgb = (b & 0xFF)
                    | ((g & 0xFF) << 8)
                    | ((r & 0xFF) << 16);
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }

}
