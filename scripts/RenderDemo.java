import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Headless renderer: runs the real PuzzleSolver pipeline on an image and
 * dumps the same animation PuzzleSolver's Swing window shows, as a PNG
 * frame sequence, without ever opening a window. Used to generate the
 * before/after screenshots and the demo GIF for the README.
 */
public class RenderDemo {

    static final int FRAMES = 60;

    public static void main(String[] args) throws Exception {
        String imagePath = args[0];
        String outDir = args[1];
        new File(outDir).mkdirs();

        BufferedImage input = ImageIO.read(new File(imagePath));
        List<PuzzleSolver.Piece> pieces = PuzzleSolver.PieceExtractor.extractPieces(input, 20, 5000);
        if (pieces.size() < 16) {
            pieces = PuzzleSolver.PieceExtractor.tileImage(input, 4, 4);
        }
        for (PuzzleSolver.Piece p : pieces) {
            p.detectAndCorrectRotation();
            p.buildOrientationsAndEdges();
        }

        PuzzleSolver.PuzzleLayout layout = PuzzleSolver.PuzzleAssembler.solve(pieces);
        if (layout == null) {
            System.err.println("Failed to solve " + imagePath);
            System.exit(1);
        }

        int canvasW = Math.max(input.getWidth(), layout.canvasWidth);
        int canvasH = Math.max(input.getHeight(), layout.canvasHeight);
        int offsetX = (canvasW - layout.puzzleWidth) / 2;
        int offsetY = (canvasH - layout.puzzleHeight) / 2;

        for (int f = 0; f < FRAMES; f++) {
            double progress = f / (double) (FRAMES - 1);
            double t = easeInOut(progress);

            BufferedImage frame = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = frame.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, canvasW, canvasH);

            for (PuzzleSolver.PlacedPiece pp : layout.placedPieces) {
                double x = lerp(pp.startX, pp.targetX + offsetX, t);
                double y = lerp(pp.startY, pp.targetY + offsetY, t);
                double angleRad = Math.toRadians(lerp(pp.startRotation, 0, t));

                int w = pp.piece.original.getWidth();
                int h = pp.piece.original.getHeight();

                AffineTransform at = new AffineTransform();
                at.translate(x + w / 2.0, y + h / 2.0);
                at.rotate(angleRad);
                at.translate(-w / 2.0, -h / 2.0);

                g2.drawImage(pp.piece.original, at, null);
            }
            g2.dispose();

            ImageIO.write(frame, "png", new File(outDir, String.format("frame_%03d.png", f)));
        }

        System.out.println("Wrote " + FRAMES + " frames to " + outDir);
    }

    static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    static double easeInOut(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }
}
