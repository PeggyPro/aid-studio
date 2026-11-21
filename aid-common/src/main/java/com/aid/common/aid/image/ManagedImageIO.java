package com.aid.common.aid.image;

import com.aid.common.config.AidAppConfig;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/** ImageIO adapter using the configured private volume for its stream caches. */
public final class ManagedImageIO {
    private ManagedImageIO() { }

    private static void configureCache() throws IOException {
        // ImageIO cache settings belong to the calling thread group.
        ImageIO.setCacheDirectory(Files.createDirectories(
            AidAppConfig.getTemporaryDirectory().resolve("imageio")).toFile());
    }

    public static ImageInputStream createImageInputStream(Object input) throws IOException {
        configureCache();
        return ImageIO.createImageInputStream(input);
    }

    public static BufferedImage read(InputStream input) throws IOException {
        configureCache();
        return ImageIO.read(input);
    }

    public static boolean write(RenderedImage image,String format,OutputStream output) throws IOException {
        configureCache();
        return ImageIO.write(image,format,output);
    }
}
