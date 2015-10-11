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
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;

import java.io.File;
import java.io.IOException;

public class TiffInfoTest {
    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffInfoTest.class.getName() + " some_tiff_file.tif");
            return;
        }
        final String fileName = args[0];
        if (fileName.equals("*")) {
            final File[] files = new File(".").listFiles();
            assert files != null;
            System.out.printf("Testing %d files%n", files.length);
            for (File f : files) {
                showTiffInfo(f);
            }
        } else {
            showTiffInfo(new File(fileName));
        }
    }

    private static void showTiffInfo(File tiffFile) throws IOException, FormatException {
        CachingTiffReader reader = new CachingTiffReader(new SCIFIO().getContext(), tiffFile);
        final int ifdCount = reader.getIFDCount();
        final long[] offsets = reader.getParser().getIFDOffsets();
        System.out.printf("%nFile %s: %d IFDs, %s, %s-endian%n",
                tiffFile,
                ifdCount,
                reader.getParser().isBigTiff() ? "BIG-TIFF" : "not big-TIFF",
                reader.getParser().getStream().isLittleEndian() ? "little" : "big");
        for (int k = 0; k < ifdCount; k++) {
            final IFD ifd = reader.getIFDByIndex(k);
            System.out.printf("%n IFD #%d/%d (offset %d=0x%X) %s[%dx%d]:%n%s",
                    k + 1, ifdCount, offsets[k], offsets[k],
                    TiffTools.javaElementType(ifd.getPixelType()).getSimpleName(),
                    ifd.getImageWidth(),
                    ifd.getImageLength(),
                    TiffTools.toString(reader.getIFDByIndex(k)));
        }
        reader.close();
        System.out.println();
    }
}
