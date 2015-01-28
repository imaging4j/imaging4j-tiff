package net.imaging4j.tiff.helper;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import io.scif.io.NIOFileHandle;
import io.scif.io.RandomAccessInputStream;
import io.scif.util.FormatTools;
import org.scijava.Context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class TiffTools {
    static final int DEBUG_LEVEL = Math.max(0, Integer.getInteger("net.imaging4j.tiff.helper.debugLevel", 1));

    private static final Map<Integer, String> IFD_TAG_NAMES = new LinkedHashMap<Integer, String>();

    static {
        // Using constants from IFD class:
        for (String javaConstant : new String[] {
                // non-IFD tags (for internal use)
                "public static final int LITTLE_ENDIAN = 0;",
                "public static final int BIG_TIFF = 1;",
                "public static final int REUSE = 3;",

                // IFD tags
                "public static final int NEW_SUBFILE_TYPE = 254;",
                "public static final int SUBFILE_TYPE = 255;",
                "public static final int IMAGE_WIDTH = 256;",
                "public static final int IMAGE_LENGTH = 257;",
                "public static final int BITS_PER_SAMPLE = 258;",
                "public static final int COMPRESSION = 259;",
                "public static final int PHOTOMETRIC_INTERPRETATION = 262;",
                "public static final int THRESHHOLDING = 263;",
                "public static final int CELL_WIDTH = 264;",
                "public static final int CELL_LENGTH = 265;",
                "public static final int FILL_ORDER = 266;",
                "public static final int DOCUMENT_NAME = 269;",
                "public static final int IMAGE_DESCRIPTION = 270;",
                "public static final int MAKE = 271;",
                "public static final int MODEL = 272;",
                "public static final int STRIP_OFFSETS = 273;",
                "public static final int ORIENTATION = 274;",
                "public static final int SAMPLES_PER_PIXEL = 277;",
                "public static final int ROWS_PER_STRIP = 278;",
                "public static final int STRIP_BYTE_COUNTS = 279;",
                "public static final int MIN_SAMPLE_VALUE = 280;",
                "public static final int MAX_SAMPLE_VALUE = 281;",
                "public static final int X_RESOLUTION = 282;",
                "public static final int Y_RESOLUTION = 283;",
                "public static final int PLANAR_CONFIGURATION = 284;",
                "public static final int PAGE_NAME = 285;",
                "public static final int X_POSITION = 286;",
                "public static final int Y_POSITION = 287;",
                "public static final int FREE_OFFSETS = 288;",
                "public static final int FREE_BYTE_COUNTS = 289;",
                "public static final int GRAY_RESPONSE_UNIT = 290;",
                "public static final int GRAY_RESPONSE_CURVE = 291;",
                "public static final int T4_OPTIONS = 292;",
                "public static final int T6_OPTIONS = 293;",
                "public static final int RESOLUTION_UNIT = 296;",
                "public static final int PAGE_NUMBER = 297;",
                "public static final int TRANSFER_FUNCTION = 301;",
                "public static final int SOFTWARE = 305;",
                "public static final int DATE_TIME = 306;",
                "public static final int ARTIST = 315;",
                "public static final int HOST_COMPUTER = 316;",
                "public static final int PREDICTOR = 317;",
                "public static final int WHITE_POINT = 318;",
                "public static final int PRIMARY_CHROMATICITIES = 319;",
                "public static final int COLOR_MAP = 320;",
                "public static final int HALFTONE_HINTS = 321;",
                "public static final int TILE_WIDTH = 322;",
                "public static final int TILE_LENGTH = 323;",
                "public static final int TILE_OFFSETS = 324;",
                "public static final int TILE_BYTE_COUNTS = 325;",
                "public static final int SUB_IFD = 330;",
                "public static final int INK_SET = 332;",
                "public static final int INK_NAMES = 333;",
                "public static final int NUMBER_OF_INKS = 334;",
                "public static final int DOT_RANGE = 336;",
                "public static final int TARGET_PRINTER = 337;",
                "public static final int EXTRA_SAMPLES = 338;",
                "public static final int SAMPLE_FORMAT = 339;",
                "public static final int S_MIN_SAMPLE_VALUE = 340;",
                "public static final int S_MAX_SAMPLE_VALUE = 341;",
                "public static final int TRANSFER_RANGE = 342;",
                "public static final int JPEG_TABLES = 347;",
                "public static final int JPEG_PROC = 512;",
                "public static final int JPEG_INTERCHANGE_FORMAT = 513;",
                "public static final int JPEG_INTERCHANGE_FORMAT_LENGTH = 514;",
                "public static final int JPEG_RESTART_INTERVAL = 515;",
                "public static final int JPEG_LOSSLESS_PREDICTORS = 517;",
                "public static final int JPEG_POINT_TRANSFORMS = 518;",
                "public static final int JPEG_Q_TABLES = 519;",
                "public static final int JPEG_DC_TABLES = 520;",
                "public static final int JPEG_AC_TABLES = 521;",
                "public static final int Y_CB_CR_COEFFICIENTS = 529;",
                "public static final int Y_CB_CR_SUB_SAMPLING = 530;",
                "public static final int Y_CB_CR_POSITIONING = 531;",
                "public static final int REFERENCE_BLACK_WHITE = 532;",
                "public static final int COPYRIGHT = 33432;",
                "public static final int EXIF = 34665;",

                // EXIF tags
                "public static final int EXPOSURE_TIME = 33434;",
                "public static final int F_NUMBER = 33437;",
                "public static final int EXPOSURE_PROGRAM = 34850;",
                "public static final int SPECTRAL_SENSITIVITY = 34852;",
                "public static final int ISO_SPEED_RATINGS = 34855;",
                "public static final int OECF = 34856;",
                "public static final int EXIF_VERSION = 36864;",
                "public static final int DATE_TIME_ORIGINAL = 36867;",
                "public static final int DATE_TIME_DIGITIZED = 36868;",
                "public static final int COMPONENTS_CONFIGURATION = 37121;",
                "public static final int COMPRESSED_BITS_PER_PIXEL = 37122;",
                "public static final int SHUTTER_SPEED_VALUE = 37377;",
                "public static final int APERTURE_VALUE = 37378;",
                "public static final int BRIGHTNESS_VALUE = 37379;",
                "public static final int EXPOSURE_BIAS_VALUE = 37380;",
                "public static final int MAX_APERTURE_VALUE = 37381;",
                "public static final int SUBJECT_DISTANCE = 37382;",
                "public static final int METERING_MODE = 37383;",
                "public static final int LIGHT_SOURCE = 37384;",
                "public static final int FLASH = 37385;",
                "public static final int FOCAL_LENGTH = 37386;",
                "public static final int MAKER_NOTE = 37500;",
                "public static final int USER_COMMENT = 37510;",
                "public static final int SUB_SEC_TIME = 37520;",
                "public static final int SUB_SEC_TIME_ORIGINAL = 37521;",
                "public static final int SUB_SEC_TIME_DIGITIZED = 37522;",
                "public static final int FLASH_PIX_VERSION = 40960;",
                "public static final int COLOR_SPACE = 40961;",
                "public static final int PIXEL_X_DIMENSION = 40962;",
                "public static final int PIXEL_Y_DIMENSION = 40963;",
                "public static final int RELATED_SOUND_FILE = 40964;",
                "public static final int FLASH_ENERGY = 41483;",
                "public static final int SPATIAL_FREQUENCY_RESPONSE = 41484;",
                "public static final int FOCAL_PLANE_X_RESOLUTION = 41486;",
                "public static final int FOCAL_PLANE_Y_RESOLUTION = 41487;",
                "public static final int FOCAL_PLANE_RESOLUTION_UNIT = 41488;",
                "public static final int SUBJECT_LOCATION = 41492;",
                "public static final int EXPOSURE_INDEX = 41493;",
                "public static final int SENSING_METHOD = 41495;",
                "public static final int FILE_SOURCE = 41728;",
                "public static final int SCENE_TYPE = 41729;",
                "public static final int CFA_PATTERN = 41730;",
                "public static final int CUSTOM_RENDERED = 41985;",
                "public static final int EXPOSURE_MODE = 41986;",
                "public static final int WHITE_BALANCE = 41987;",
                "public static final int DIGITAL_ZOOM_RATIO = 41988;",
                "public static final int FOCAL_LENGTH_35MM_FILM = 41989;",
                "public static final int SCENE_CAPTURE_TYPE = 41990;",
                "public static final int GAIN_CONTROL = 41991;",
                "public static final int CONTRAST = 41992;",
                "public static final int SATURATION = 41993;",
                "public static final int SHARPNESS = 41994;",
                "public static final int SUBJECT_DISTANCE_RANGE = 41996;",
        })
        {
            addIFdTagName(javaConstant);
        }
    }

    private TiffTools() {
    }

    public static byte[] interleaveSamples(byte[] samples, int bandSizeInPixels, IFD ifd) throws FormatException {
        final int bandCount = ifd.getSamplesPerPixel();
        final int pixelType = ifd.getPixelType();
        final int bytesPerBand = Math.max(1, FormatTools.getBytesPerPixel(pixelType));
        return interleaveSamples(samples, bandSizeInPixels, bandCount, bytesPerBand);

    }

    public static byte[] interleaveSamples(byte[] samples, int bandSizeInPixels, int bandCount, int bytesPerBand) {
        if (samples == null) {
            throw new NullPointerException("Null samples");
        }
        if (bandSizeInPixels < 0) {
            throw new IllegalArgumentException("Negative bandSizeInPixels = " + bandSizeInPixels);
        }
        if (bandCount <= 0) {
            throw new IllegalArgumentException("Zero or negative bandCount = " + bandCount);
        }
        if (bytesPerBand <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerBand = " + bytesPerBand);
        }
        if (bandCount == 1) {
            return samples;
        }
        final int bandSize = bandSizeInPixels * bytesPerBand;
        byte[] interleavedBytes = new byte[samples.length];
        if (bytesPerBand == 1) {
            // optimization
            for (int i = 0, disp = 0; i < bandSize; i++) {
                for (int bandDisp = i; bandDisp < samples.length; bandDisp += bandSize) {
                    interleavedBytes[disp++] = samples[bandDisp];
                }
            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerBand) {
                for (int bandDisp = i; bandDisp < samples.length; bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerBand; k++) {
                        interleavedBytes[disp++] = samples[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }

    public static RandomAccessInputStream newSimpleRandomAccessInputStream(Context context, File file)
            throws IOException
    {
        return new RandomAccessInputStream(context,
//            tiffFile.getAbsolutePath());
                new NIOFileHandle(new SimpleNIOService(), file, "r", 65536));
        // We will use this object only for parsing TIFF, so, we don't need large buffer here
    }

    public static void checkThatIfdSizesArePositiveIntegers(IFD ifd) throws FormatException {
        long dimX = ifd.getImageWidth();
        long dimY = ifd.getImageLength();
        if (dimX > Integer.MAX_VALUE || dimY > Integer.MAX_VALUE) {
            throw new FormatException("Too large image " + dimX + "x" + dimY
                    + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
        // - probably impossible and cannot be processed correctly,
        // because TiffParser.getSamples method has int x and y arguments
        if (dimX <= 0 || dimY <= 0) {
            throw new FormatException("Zero or negative IFD image sizes " + dimX + "x" + dimY
                    + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
        // - important in many PlanePyramidSource implementations for using IRectangularArea with IFD images
        // (IRectangularArea cannot describe the empty set)
    }

    public static void checkIfdSizes(final IFD ifd, final int width, final int height) throws FormatException {
        if (ifd == null) {
            throw new NullPointerException("Null ifd argument");
        }
        if (width < 0) {
            throw new IllegalArgumentException("Zero or negative width");
        }
        if (height < 0) {
            throw new IllegalArgumentException("Zero or negative height");
        }
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final int bytesPerSample = ifd.getBytesPerSample()[0];
        if ((long) width * (long) height > Integer.MAX_VALUE
                || (long) width * (long) height * (long) bytesPerSample > Integer.MAX_VALUE
                || (long) width * (long) height * (long) bytesPerSample * (long) samplesPerPixel > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Too large requested area: "
                    + "width * height * bytes per sample * samples per pixel = "
                    + width + " * " + height + " * " + bytesPerSample + " * " + samplesPerPixel + " >= 2^31"
                    + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
    }

    public static Class<?> javaElementType(int ifdPixelType) {
        switch (ifdPixelType) {
            case FormatTools.INT8:
            case FormatTools.UINT8:
                return byte.class;
            case FormatTools.INT16:
            case FormatTools.UINT16:
                return short.class;
            case FormatTools.INT32:
            case FormatTools.UINT32:
                return int.class;
            case FormatTools.FLOAT:
                return float.class;
            case FormatTools.DOUBLE:
                return double.class;
        }
        throw new IllegalArgumentException("Unknown pixel type: " + ifdPixelType);
    }

    public static void checkIfdElementType(final IFD ifd, Class<?> requiredElementType) throws FormatException {
        final Class<?> ifdElementType = javaElementType(ifd.getPixelType());
        if (ifdElementType != requiredElementType) {
            throw new FormatException("Invalid element types: \""
                    + ifdElementType + "\" instead of \"" + requiredElementType + "\""
                    + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
    }

    public static void checkIfdSamplesPerPixel(final IFD ifd, int requiredSamplesPerPixel) throws FormatException {
        final int ifdBandCount = ifd.getSamplesPerPixel();
        if (ifdBandCount != requiredSamplesPerPixel) {
            throw new FormatException("Invalid number of samples per pixel: "
                    + ifdBandCount + " instead of " + requiredSamplesPerPixel
                    + " (image description " + ifd.get(IFD.IMAGE_DESCRIPTION) + ")");
        }
    }

    public static String ifdTagName(int tag) {
        final String name = IFD_TAG_NAMES.get(tag);
        return (name == null ? "Unknown tag" : name) + " (0x" + Integer.toHexString(tag) + ")";
    }

    public static String toString(IFD ifd) {
        StringBuilder sb = new StringBuilder();
        final Map<Integer, Object> sortedIFD = new TreeMap<Integer, Object>(ifd);
        for (Map.Entry<Integer, Object> entry : sortedIFD.entrySet()) {
            final Integer key = entry.getKey();
            final Object value = entry.getValue();
            Object additional = null;
            try {
                switch (key) {
                    case IFD.PHOTOMETRIC_INTERPRETATION:
                        additional = ifd.getPhotometricInterpretation();
                        break;
                    case IFD.COMPRESSION:
                        additional = ifd.getCompression();
                        break;
                }
            } catch (FormatException e) {
                additional = e;
            }
            sb.append("    ").append(ifdTagName(key)).append(" = ");
            if (value != null && value.getClass().isArray()) {
                final int len = Array.getLength(value);
                sb.append(value.getClass().getComponentType().getSimpleName()).append("[").append(len).append("]");
                if (len <= 16) {
                    sb.append(" {").append(Array.get(value, 0));
                    for (int k = 1; k < len; k++) {
                        sb.append("; ").append(Array.get(value, k));
                    }
                    sb.append("}");
                }
            } else {
                sb.append(value);
            }
            if (additional != null) {
                sb.append(" [").append(additional).append("]");
            }
            sb.append(String.format("%n"));
        }
        return sb.toString();
    }

    static void debug(int level, String format, Object... args) {
        if (DEBUG_LEVEL >= level) {
            System.out.printf(Locale.US, format, args);
        }
    }

    private static void addIFdTagName(String javaConstant) {
        int p = javaConstant.indexOf("=");
        assert p != -1;
        String name = javaConstant.substring(0, p).trim();
        if (name.startsWith("public static final int")) {
            name = name.substring("public static final int".length()).trim();
        }
        String id = javaConstant.substring(p + 1).trim();
        if (id.endsWith(";")) {
            id = id.substring(0, id.length() - ";".length()).trim();
        }
        IFD_TAG_NAMES.put(Integer.parseInt(id), name);
    }
}
