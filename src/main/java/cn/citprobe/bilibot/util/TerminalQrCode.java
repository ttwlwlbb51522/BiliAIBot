package cn.citprobe.bilibot.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 在终端渲染二维码，并同时支持保存 PNG 备用。
 */
public final class TerminalQrCode {

    private static final int QUIET_ZONE = 2;

    private TerminalQrCode() {
    }

    public static void print(String content) {
        BitMatrix matrix = encode(content);
        System.out.print("\033[30;47m"); // 黑字、白底
        for (int y = -QUIET_ZONE; y < matrix.getHeight() + QUIET_ZONE; y++) {
            for (int x = -QUIET_ZONE; x < matrix.getWidth() + QUIET_ZONE; x++) {
                boolean black = x >= 0 && y >= 0
                        && x < matrix.getWidth() && y < matrix.getHeight()
                        && matrix.get(x, y);
                System.out.print(black ? '█' : ' ');
            }
            System.out.println();
        }
        System.out.print("\033[0m");
        System.out.println();
    }

    /**
     * 保存二维码 PNG，方便终端二维码显示异常或需要在另一台设备上查看。
     */
    public static Path savePng(String content, Path file) throws IOException {
        BitMatrix matrix = encode(content);
        int scale = 8;
        int width = (matrix.getWidth() + QUIET_ZONE * 2) * scale;
        int height = (matrix.getHeight() + QUIET_ZONE * 2) * scale;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) {
                        graphics.fillRect((x + QUIET_ZONE) * scale, (y + QUIET_ZONE) * scale,
                                scale, scale);
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "PNG", absolute.toFile());
        return absolute;
    }

    private static BitMatrix encode(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints);
        } catch (WriterException | IllegalArgumentException e) {
            throw new IllegalStateException("生成二维码失败: " + e.getMessage(), e);
        }
    }
}
