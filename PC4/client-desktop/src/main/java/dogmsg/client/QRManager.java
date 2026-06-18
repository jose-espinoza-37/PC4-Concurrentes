package dogmsg.client;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;


public class QRManager {

    /** Genera una imagen QR (cuadrada) que codifica el texto dado. */
    public BufferedImage generate(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new MultiFormatWriter()
                .encode(text, BarcodeFormat.QR_CODE, size, size, hints);

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int black = 0x000000;
        int white = 0xFFFFFF;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, matrix.get(x, y) ? black : white);
            }
        }
        return img;
    }

    /** Guarda el QR generado a un archivo PNG (opcional). */
    public void generateToFile(String text, int size, File out) throws Exception {
        ImageIO.write(generate(text, size), "png", out);
    }

    /** Lee y decodifica un QR desde un archivo de imagen. */
    public String readFromFile(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IllegalArgumentException("No se pudo leer la imagen: " + file);
        }
        return decode(image);
    }

    /** Decodifica un QR contenido en una BufferedImage (camara o archivo). */
    public String decode(BufferedImage image) throws Exception {
        BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        try {
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("No se encontro ningun QR en la imagen", e);
        }
    }
}