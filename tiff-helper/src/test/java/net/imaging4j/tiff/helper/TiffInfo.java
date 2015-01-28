package net.imaging4j.tiff.helper;


import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;

import java.io.File;
import java.io.IOException;

public class TiffInfo {
    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffInfo.class.getName() + " some_tiff_file.tif");
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
            System.out.printf("%n IFD #%d/%d (offset %d) %s[%dx%d]:%n%s",
                    k + 1, ifdCount, offsets[k],
                    TiffTools.javaElementType(ifd.getPixelType()).getSimpleName(),
                    ifd.getImageWidth(),
                    ifd.getImageLength(),
                    TiffTools.toString(reader.getIFDByIndex(k)));
        }
        reader.close();
        System.out.println();
    }
}
