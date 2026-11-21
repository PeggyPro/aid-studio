package com.aid.common.aid.image;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 确定性图层合成器。逐层读取并释放源图，输出保留透明通道的 PNG。 */
public final class ImageLayerCompositor {
    public static final int MAX_LAYERS = 25;

    private ImageLayerCompositor() { }

    @FunctionalInterface
    public interface ImageSource {
        byte[] read() throws IOException;
    }

    public record Rect(double x, double y, double width, double height) { }

    public record Layer(String id, ImageSource source, Rect rect, double rotationDeg,
                        boolean flipX, boolean flipY, float opacity, boolean visible) { }

    public record Result(int width, int height, byte[] png) { }

    public static Result compose(int width, int height, ImageSource background, List<Layer> layers)
            throws IOException {
        if (width < 1 || height < 1 || (long) width * height > ImageRegionProcessor.MAX_PIXELS) {
            throw new IllegalArgumentException("合成画布尺寸无效");
        }
        if (layers == null || layers.isEmpty() || layers.size() > MAX_LAYERS) {
            throw new IllegalArgumentException("合成图层数量无效");
        }
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            configure(graphics);
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, width, height);
            graphics.setComposite(AlphaComposite.SrcOver);
            if (background != null) drawContained(graphics, decode(background.read()), 0, 0, width, height,
                    0, false, false, 1f);
            for (Layer layer : layers) {
                validate(layer);
                if (!layer.visible() || layer.opacity() <= 0f) continue;
                Rect rect = layer.rect();
                double x = rect.x() * width;
                double y = rect.y() * height;
                double targetWidth = rect.width() * width;
                double targetHeight = rect.height() * height;
                drawContained(graphics, decode(layer.source().read()), x, y, targetWidth, targetHeight,
                        layer.rotationDeg(), layer.flipX(), layer.flipY(), layer.opacity());
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ManagedImageIO.write(output, "png", bytes)) throw new IOException("PNG 编码器不可用");
        return new Result(width, height, bytes.toByteArray());
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
    }

    private static void validate(Layer layer) {
        if (layer == null || layer.id() == null || layer.id().isBlank() || layer.source() == null
                || layer.rect() == null || !finite(layer.rotationDeg()) || !finite(layer.opacity())
                || layer.opacity() < 0f || layer.opacity() > 1f) {
            throw new IllegalArgumentException("合成图层参数无效");
        }
        Rect rect = layer.rect();
        if (!finite(rect.x()) || !finite(rect.y()) || !finite(rect.width()) || !finite(rect.height())
                || rect.x() < 0 || rect.y() < 0 || rect.width() <= 0 || rect.height() <= 0
                || rect.x() + rect.width() > 1.000001 || rect.y() + rect.height() > 1.000001) {
            throw new IllegalArgumentException("合成图层位置越界");
        }
    }

    private static void drawContained(Graphics2D graphics, BufferedImage source,
            double x, double y, double targetWidth, double targetHeight, double rotationDeg,
            boolean flipX, boolean flipY, float opacity) {
        double scale = Math.min(targetWidth / source.getWidth(), targetHeight / source.getHeight());
        double drawWidth = Math.max(1d, source.getWidth() * scale);
        double drawHeight = Math.max(1d, source.getHeight() * scale);
        double centerX = x + targetWidth / 2d;
        double centerY = y + targetHeight / 2d;
        AffineTransform previousTransform = graphics.getTransform();
        var previousComposite = graphics.getComposite();
        try {
            graphics.setComposite(AlphaComposite.SrcOver.derive(opacity));
            graphics.translate(centerX, centerY);
            graphics.rotate(Math.toRadians(rotationDeg));
            graphics.scale(flipX ? -1d : 1d, flipY ? -1d : 1d);
            graphics.drawImage(source, (int) Math.round(-drawWidth / 2d), (int) Math.round(-drawHeight / 2d),
                    (int) Math.round(drawWidth), (int) Math.round(drawHeight), null);
        } finally {
            graphics.setTransform(previousTransform);
            graphics.setComposite(previousComposite);
            source.flush();
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图层图片为空");
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ManagedImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) throw new IllegalArgumentException("图层图片格式无效");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("图层图片格式无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > ImageRegionProcessor.MAX_PIXELS) {
                    throw new IllegalArgumentException("图层图片尺寸无效");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
