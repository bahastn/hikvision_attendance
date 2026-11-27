package com.attendance.service.insightface;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Small JDK-only helpers to normalize uploaded photos to JPEG. */
public final class JpegUtil {

    private JpegUtil() {
    }

    /**
     * Convert any ImageIO-readable image (JPEG/PNG/BMP/GIF) to JPEG, scaling it down so the longer
     * side is at most {@code maxSide} pixels ({@code <= 0} = never scale). A JPEG that is already
     * small enough is returned unchanged.
     *
     * @throws IllegalArgumentException when the bytes are not a readable image
     */
    public static byte[] toJpeg(byte[] bytes, int maxSide) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("Photo file is empty");
        }
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(bytes));
            if (read == null) {
                throw new IllegalArgumentException("Unsupported photo format — please upload a JPEG or PNG image");
            }
            boolean isJpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
            int longSide = Math.max(read.getWidth(), read.getHeight());
            boolean scale = maxSide > 0 && longSide > maxSide;
            if (isJpeg && !scale) {
                return bytes;
            }
            double factor = scale ? (double) maxSide / longSide : 1.0;
            int w = Math.max(1, (int) Math.round(read.getWidth() * factor));
            int h = Math.max(1, (int) Math.round(read.getHeight() * factor));
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(read, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the photo — please upload a JPEG or PNG image");
        }
    }
}
