import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class PuzzleSolver {

    private static final String IMAGE_PATH = "images/test2.png";
    private static final boolean ENABLE_GRID_SPLIT_FALLBACK = true;
    private static final int GRID_ROWS = 4;
    private static final int GRID_COLS = 4;
    private static final int FOREGROUND_THRESHOLD = 20;
    private static final int MIN_COMPONENT_SIZE = 5000;
    private static final boolean USE_IRREGULAR_SOLVER = true;  
    private static final int ANIMATION_FRAMES = 300;
    private static final int ANIMATION_FPS = 60;
    private static final int EDGE_WIDTH = 20;  
    private static final int EDGE_SAMPLE_DEPTH = 8; 
    private static final long TIMEOUT_MS = 10 * 60 * 1000;  
    private static final int BORDER_TRIM_PIXELS = 2; 

    private static long startTime;
    private static volatile boolean timedOut = false;

    public static void main(String[] args) {
        startTime = System.currentTimeMillis();
        Timer timeoutTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = TIMEOUT_MS - elapsed;
                if (remaining <= 0) {
                    timedOut = true;
                    System.out.println("TIMEOUT: 10 minutes reached!");
                } else {
                    int minutes = (int) (remaining / 60000);
                    int seconds = (int) ((remaining % 60000) / 1000);
                    System.out.println("Time remaining: " + minutes + "m " + seconds + "s");
                }
            }
        });
        timeoutTimer.start();

        try {
            String imagePath = (args != null && args.length > 0) ? args[0] : IMAGE_PATH;
            BufferedImage input = ImageIO.read(new File(imagePath));
            System.out.println("Loaded image: " + imagePath +
                               " (" + input.getWidth() + " x " + input.getHeight() + ")");

            List<Piece> pieces = PieceExtractor.extractPieces(input, FOREGROUND_THRESHOLD, MIN_COMPONENT_SIZE);
            if (ENABLE_GRID_SPLIT_FALLBACK && pieces.size() < GRID_ROWS * GRID_COLS) {
                System.out.println("Too few components detected (" + pieces.size() +
                        "); forcing uniform grid split of " + GRID_ROWS + "x" + GRID_COLS + ".");
                pieces = PieceExtractor.tileImage(input, GRID_ROWS, GRID_COLS);
            }
            System.out.println("Detected pieces: " + pieces.size());

            if (pieces.isEmpty()) {
                System.err.println("No pieces detected.");
                return;
            }

            for (Piece p : pieces) {
                p.detectAndCorrectRotation();
                p.buildOrientationsAndEdges();
            }

            PuzzleLayout layout = PuzzleAssembler.solve(pieces);
            if (layout == null) {
                System.err.println("Failed to find a valid puzzle arrangement.");
                return;
            }

            timeoutTimer.stop();
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("Total time: " + (totalTime / 1000) + " seconds");

            AnimationWindow.show(input, layout);

        } catch (IOException e) {
            System.err.println("Failed to load image: " + e.getMessage());
        }
    }

    private static boolean isTimedOut() {
        return timedOut || (System.currentTimeMillis() - startTime > TIMEOUT_MS);
    }

    static class Piece {
        int id;
        BufferedImage original;
        Rectangle originalBounds;
        OrientedPiece[] orientations = new OrientedPiece[4];
        double detectedRotation = 0;

        public Piece(int id, BufferedImage original, Rectangle bounds) {
            this.id = id;
            this.original = original;
            this.originalBounds = bounds;
        }

        public void detectAndCorrectRotation() {
            // FIRST: Crop tight to remove black padding
            original = cropTight(original);

            // Detect the rotation angle of the piece 
            detectedRotation = detectRotationAngle(original);
            System.out.println("Piece " + id + " detected rotation: " + detectedRotation + " degrees");

            // Rotate the piece to make it straight (0 degrees)
            if (Math.abs(detectedRotation) >= 1.0) {
                original = rotateImage(original, -detectedRotation);
                original = cropTight(original);  // Crop again after rotation
                System.out.println("Piece " + id + " corrected to straight");
            }

            // Final crop to ensure no black borders
            original = cropTight(original);

            if (Math.abs(detectedRotation) >= 1.0) {
                original = trimBorder(original, BORDER_TRIM_PIXELS);
            }
        }

        private BufferedImage cropTight(BufferedImage src) {
            int w = src.getWidth();
            int h = src.getHeight();
            int minX = w, maxX = 0, minY = h, maxY = 0;
            boolean found = false;

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = src.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r + g + b > 30) {
                        found = true;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }

            if (!found) return src;

            int cw = maxX - minX + 1;
            int ch = maxY - minY + 1;
            BufferedImage dst = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();

            // Sample edge color from the crop region
            long rSum = 0, gSum = 0, bSum = 0;
            int count = 0;
            for (int x = minX; x <= maxX; x++) {
                int rgb = src.getRGB(x, minY);
                rSum += (rgb >> 16) & 0xFF;
                gSum += (rgb >> 8) & 0xFF;
                bSum += rgb & 0xFF;
                count++;
            }
            Color edgeColor = new Color((int)(rSum/count), (int)(gSum/count), (int)(bSum/count));
            g.setColor(edgeColor);
            g.fillRect(0, 0, cw, ch);
            g.drawImage(src, 0, 0, cw, ch, minX, minY, maxX+1, maxY+1, null);
            g.dispose();
            return dst;
        }

        private boolean hasDarkEdges(BufferedImage img) {
            int w = img.getWidth();
            int h = img.getHeight();
            int darkThreshold = 50;  
            int darkCount = 0;
            int totalSampled = 0;

            // Sample top and bottom edges
            for (int x = 0; x < w; x++) {
                // Top edge
                int rgb = img.getRGB(x, 0);
                int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum < darkThreshold) darkCount++;
                totalSampled++;

                // Bottom edge
                rgb = img.getRGB(x, h - 1);
                sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum < darkThreshold) darkCount++;
                totalSampled++;
            }

            // Sample left and right edges
            for (int y = 0; y < h; y++) {
                // Left edge
                int rgb = img.getRGB(0, y);
                int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum < darkThreshold) darkCount++;
                totalSampled++;

                // Right edge
                rgb = img.getRGB(w - 1, y);
                sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum < darkThreshold) darkCount++;
                totalSampled++;
            }
            return (darkCount * 100.0 / totalSampled) > 5.0;
        }

        private BufferedImage trimBorder(BufferedImage src, int borderPixels) {
            int w = src.getWidth();
            int h = src.getHeight();

            // Check if image is large enough to trim
            if (w <= 2 * borderPixels || h <= 2 * borderPixels) {
                return src; 
            }

            int newW = w - 2 * borderPixels;
            int newH = h - 2 * borderPixels;

            BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.drawImage(src, 0, 0, newW, newH,
                        borderPixels, borderPixels,
                        w - borderPixels, h - borderPixels, null);
            g.dispose();

            return dst;
        }

        private double detectRotationAngle(BufferedImage img) {
            int w = img.getWidth();
            int h = img.getHeight();
            List<Point> boundaryPoints = new ArrayList<>();

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r + g + b > 30) {
                        boolean isBoundary = false;
                        for (int dy = -1; dy <= 1 && !isBoundary; dy++) {
                            for (int dx = -1; dx <= 1 && !isBoundary; dx++) {
                                if (dx == 0 && dy == 0) continue;
                                int nx = x + dx;
                                int ny = y + dy;
                                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                                    int nrgb = img.getRGB(nx, ny);
                                    int nr = (nrgb >> 16) & 0xFF;
                                    int ng = (nrgb >> 8) & 0xFF;
                                    int nb = nrgb & 0xFF;
                                    if (nr + ng + nb <= 30) {
                                        isBoundary = true;
                                    }
                                }
                            }
                        }
                        if (isBoundary) {
                            boundaryPoints.add(new Point(x, y));
                        }
                    }
                }
            }

            if (boundaryPoints.size() < 20) return 0;

            // Sample random pairs to find dominant orientation
            double[] angleHistogram = new double[360];  
            Random rng = new Random(42);
            int samples = Math.min(10000, boundaryPoints.size() * 10);

            for (int i = 0; i < samples; i++) {
                Point p1 = boundaryPoints.get(rng.nextInt(boundaryPoints.size()));
                Point p2 = boundaryPoints.get(rng.nextInt(boundaryPoints.size()));
                if (p1 == p2) continue;

                double dx = p2.x - p1.x;
                double dy = p2.y - p1.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 10) continue;  

                double angle = Math.toDegrees(Math.atan2(dy, dx));
                angle = (angle + 360) % 180;  

                int idx = (int) Math.round(angle * 2) % 360;  
                if (idx >= 180) idx -= 180;  
                angleHistogram[idx] += dist;  
            }

            int maxIdx = 0;
            for (int i = 0; i < 180; i++) {
                if (angleHistogram[i] > angleHistogram[maxIdx]) {
                    maxIdx = i;
                }
            }

            // Convert from bin index back to degrees (we used 0.5 degree bins)
            double dominantAngle = maxIdx / 2.0;

            // Normalize to [-45, 45] range for small corrections
            if (dominantAngle > 90) dominantAngle -= 180;
            if (dominantAngle > 45) dominantAngle -= 90;
            if (dominantAngle < -45) dominantAngle += 90;

            return dominantAngle;
        }

        public void buildOrientationsAndEdges() {
            int[] angles = {0, 90, 180, 270};
            for (int i = 0; i < 4; i++) {
                int angle = angles[i];
                BufferedImage rotated = rotateImage(original, angle);
                OrientedPiece op = new OrientedPiece(this, angle, rotated);
                op.computeEdgeFeatures();
                orientations[i] = op;
            }
        }

        private BufferedImage rotateImage(BufferedImage src, double angleDegrees) {
            if (Math.abs(angleDegrees) < 0.1) return src;

            double angle = Math.toRadians(angleDegrees);
            int w = src.getWidth();
            int h = src.getHeight();

            // Calculate new dimensions
            double cos = Math.abs(Math.cos(angle));
            double sin = Math.abs(Math.sin(angle));
            int newW = (int) Math.ceil(w * cos + h * sin);
            int newH = (int) Math.ceil(w * sin + h * cos);

            BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, newW, newH);

            AffineTransform at = new AffineTransform();
            at.translate(newW / 2.0, newH / 2.0);
            at.rotate(angle);
            at.translate(-w / 2.0, -h / 2.0);

            g2.drawRenderedImage(src, at);
            g2.dispose();
            return dst;
        }
    }

    static class OrientedPiece {
        Piece parent;
        int rotation;
        BufferedImage image;
        EdgeFeature[] edges = new EdgeFeature[4];

        public OrientedPiece(Piece parent, int rotation, BufferedImage image) {
            this.parent = parent;
            this.rotation = rotation;
            this.image = image;
        }

        public void computeEdgeFeatures() {
            int w = image.getWidth();
            int h = image.getHeight();
            edges[0] = computeEdge(0, 0, 1, 0, w, EDGE_WIDTH);     
            edges[1] = computeEdge(w - 1, 0, 0, 1, h, EDGE_WIDTH);  
            edges[2] = computeEdge(0, h - 1, 1, 0, w, EDGE_WIDTH);  
            edges[3] = computeEdge(0, 0, 0, 1, h, EDGE_WIDTH);    
        }

        private EdgeFeature computeEdge(int startX, int startY, int stepX, int stepY, int length, int width) {
            // Sample inward from edge to avoid rotation artifacts
            int w = image.getWidth();
            int h = image.getHeight();

            float[] feat = new float[length * 3];
            int idx = 0;

            for (int i = 0; i < length; i++) {
                int x = startX + stepX * i;
                int y = startY + stepY * i;

                // Sample INWARD from the edge 
                int sumR = 0, sumG = 0, sumB = 0, count = 0;

                for (int d = 0; d < EDGE_SAMPLE_DEPTH; d++) { 
                    int px, py;

                    if (stepX != 0) {  
                        px = x;
                        if (startY == 0) {
                            py = d;  
                        } else {
                            py = h - 1 - d;  
                        }
                    } else {  
                        py = y;
                        if (startX == 0) {
                            px = d;  
                        } else {
                            px = w - 1 - d; 
                        }
                    }

                    // Check bounds
                    if (px >= 0 && px < w && py >= 0 && py < h) {
                        int rgb = image.getRGB(px, py);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;

                        // Skip near-black pixels
                        if (r + g + b >= 30) {
                            sumR += r;
                            sumG += g;
                            sumB += b;
                            count++;
                        }
                    }
                }

                // Average the clean pixels
                if (count > 0) {
                    feat[idx++] = (float)(sumR / count);
                    feat[idx++] = (float)(sumG / count);
                    feat[idx++] = (float)(sumB / count);
                } else {
                    // Fallback to border pixel
                    int px = Math.max(0, Math.min(w - 1, x));
                    int py = Math.max(0, Math.min(h - 1, y));
                    int rgb = image.getRGB(px, py);
                    feat[idx++] = (rgb >> 16) & 0xFF;
                    feat[idx++] = (rgb >> 8) & 0xFF;
                    feat[idx++] = rgb & 0xFF;
                }
            }

            return new EdgeFeature(feat);
        }
    }

    static class EdgeFeature {
        float[] data;

        public EdgeFeature(float[] data) {
            this.data = data;
        }

        // Fiend's matching algorithm: pixel diff + histograms
        public static double mse(EdgeFeature a, EdgeFeature b) {
            return computeMatchScore(a.data, b.data);
        }

        private static double computeMatchScore(float[] d1, float[] d2) {
            int n = Math.min(d1.length / 3, d2.length / 3);
            if (n == 0) return Double.MAX_VALUE;

            double colorSum = 0;
            double gradientSum = 0;
            int count = 0;

            // Direct pixel RGB difference + gradient matching
            for (int i = 0; i < n; i++) {
                float r1 = d1[i * 3];
                float g1 = d1[i * 3 + 1];
                float b1 = d1[i * 3 + 2];

                float r2 = d2[i * 3];
                float g2 = d2[i * 3 + 1];
                float b2 = d2[i * 3 + 2];
                if (r1 + g1 + b1 < 30 || r2 + g2 + b2 < 30) continue;

                // Color difference
                double dr = r1 - r2;
                double dg = g1 - g2;
                double db = b1 - b2;
                colorSum += Math.sqrt(dr*dr + dg*dg + db*db);

                // Gradient difference (helps with texture matching)
                if (i > 0 && i < n - 1) {
                    float r1Prev = d1[(i-1) * 3];
                    float r1Next = d1[(i+1) * 3];
                    float r2Prev = d2[(i-1) * 3];
                    float r2Next = d2[(i+1) * 3];

                    double grad1 = (r1Next - r1Prev) / 2.0;
                    double grad2 = (r2Next - r2Prev) / 2.0;
                    gradientSum += Math.abs(grad1 - grad2);
                }

                count++;
            }

            if (count == 0) return Double.MAX_VALUE;
            return (0.7 * colorSum + 0.3 * gradientSum) / count;
        }
    }

    static class PlacedPiece {
        Piece piece;
        int rotation;
        int targetX, targetY;
        int startX, startY;
        int startRotation;

        public PlacedPiece(Piece p, int rot, int tx, int ty) {
            this.piece = p;
            this.rotation = rot;
            this.targetX = tx;
            this.targetY = ty;
            // Start from the piece's original position in the scrambled input image
            this.startX = p.originalBounds.x;
            this.startY = p.originalBounds.y;
            // Start rotation is the detected rotation angle 
            this.startRotation = (int) Math.round(p.detectedRotation);
        }
    }

    static class PuzzleLayout {
        List<PlacedPiece> placedPieces = new ArrayList<>();
        int canvasWidth;
        int canvasHeight;
        int puzzleWidth;
        int puzzleHeight;
    }

    static class PieceExtractor {
        private static final int BG_COLOR_TOLERANCE = 40;

        public static List<Piece> extractPieces(BufferedImage img, int foregroundThreshold, int minComponentSize) {
            int w = img.getWidth();
            int h = img.getHeight();
            boolean[][] visited = new boolean[h][w];

            int bgRgb = img.getRGB(0, 0);
            int bgR = (bgRgb >> 16) & 0xFF;
            int bgG = (bgRgb >> 8) & 0xFF;
            int bgB = bgRgb & 0xFF;

            List<Piece> pieces = new ArrayList<>();
            int idCounter = 0;

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!visited[y][x] && isForeground(img, x, y, bgR, bgG, bgB)) {
                        List<Point> component = new ArrayList<>();
                        Queue<Point> q = new LinkedList<>();
                        visited[y][x] = true;
                        q.add(new Point(x, y));
                        component.add(new Point(x, y));

                        int minX = x, maxX = x;
                        int minY = y, maxY = y;

                        int[] dx = {1, -1, 0, 0};
                        int[] dy = {0, 0, 1, -1};

                        while (!q.isEmpty()) {
                            Point p = q.remove();
                            for (int dir = 0; dir < 4; dir++) {
                                int nx = p.x + dx[dir];
                                int ny = p.y + dy[dir];
                                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                                    if (!visited[ny][nx] && isForeground(img, nx, ny, bgR, bgG, bgB)) {
                                        visited[ny][nx] = true;
                                        Point np = new Point(nx, ny);
                                        q.add(np);
                                        component.add(np);
                                        if (nx < minX) minX = nx;
                                        if (nx > maxX) maxX = nx;
                                        if (ny < minY) minY = ny;
                                        if (ny > maxY) maxY = ny;
                                    }
                                }
                            }
                        }

                        if (component.size() < minComponentSize) continue;

                        int compW = maxX - minX + 1;
                        int compH = maxY - minY + 1;
                        BufferedImage pieceImg = new BufferedImage(compW, compH, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2 = pieceImg.createGraphics();
                        g2.drawImage(img, 0, 0, compW, compH, minX, minY, maxX + 1, maxY + 1, null);
                        g2.dispose();

                        pieces.add(new Piece(idCounter++, pieceImg, new Rectangle(minX, minY, compW, compH)));
                    }
                }
            }
            return pieces;
        }

        private static boolean isForeground(BufferedImage img, int x, int y, int bgR, int bgG, int bgB) {
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int dr = r - bgR, dg = g - bgG, db = b - bgB;
            return dr * dr + dg * dg + db * db > BG_COLOR_TOLERANCE * BG_COLOR_TOLERANCE;
        }

        public static List<Piece> tileImage(BufferedImage img, int rows, int cols) {
            List<Piece> pieces = new ArrayList<>();
            int w = img.getWidth();
            int h = img.getHeight();
            int cellW = w / cols;
            int cellH = h / rows;
            int id = 0;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int x = c * cellW;
                    int y = r * cellH;
                    int cw = (c == cols - 1) ? (w - x) : cellW;
                    int ch = (r == rows - 1) ? (h - y) : cellH;

                    BufferedImage pieceImg = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = pieceImg.createGraphics();
                    g2.drawImage(img, 0, 0, cw, ch, x, y, x + cw, y + ch, null);
                    g2.dispose();

                    pieces.add(new Piece(id++, pieceImg, new Rectangle(x, y, cw, ch)));
                }
            }
            return pieces;
        }
    }

    static class PuzzleAssembler {

        static class BestSolution {
            OrientedPiece[][] bestGrid = null;
            double bestCost = Double.POSITIVE_INFINITY;
            int iterations = 0;
        }

        public static PuzzleLayout solve(List<Piece> pieces) {
            int n = pieces.size();
            if (n == 0) return null;

            // Detect irregular pieces
            boolean isIrregular = isIrregularPuzzle(pieces);

            if (isIrregular && USE_IRREGULAR_SOLVER) {
                System.out.println("\n=== IRREGULAR PIECES: USING LOCAL+GLOBAL SEARCH WITH ROTATION ===");
                return solveIrregular(pieces);
            }

            System.out.println("\n=== REGULAR PIECES: USING GRAPH ALGORITHM ===");

            List<EdgeMatch> matches = performEdgeMatching(pieces);
            Map<Integer, Map<EdgeDir, PlacementEdge>> graph = buildAdjacencyGraph(matches, n);
            Map<Integer, GridPos> layout = computeLayoutFromGraph(graph, n);
            layout = fixOutOfBoundsPieces(layout, n);
            layout = optimizePlacement(layout, pieces);
            OrientedPiece[][] grid = convertLayoutToGrid(layout, pieces);

            return buildLayoutFromGrid(grid);
        }

        private static boolean isIrregularPuzzle(List<Piece> pieces) {
            if (pieces.isEmpty()) return false;

            int firstW = pieces.get(0).original.getWidth();
            int firstH = pieces.get(0).original.getHeight();

            for (Piece p : pieces) {
                int w = p.original.getWidth();
                int h = p.original.getHeight();
                if (Math.abs(w - firstW) > firstW * 0.1 || Math.abs(h - firstH) > firstH * 0.1) {
                    System.out.println("Detected irregular pieces (size variance)");
                    return true;
                }
            }
            return false;
        }

        // IRREGULAR SOLVER: Edge matching with rotation + local+global optimization
        private static PuzzleLayout solveIrregular(List<Piece> pieces) {
            int n = pieces.size();
            int rows = GRID_ROWS;
            int cols = GRID_COLS;

            System.out.println("Phase 1: Finding best edge matches across all rotations...");
            List<RotationMatch> allMatches = findBestRotationMatches(pieces);
            System.out.println("Phase 2: Building initial grid from best matches...");
            OrientedPiece[][] initialGrid = buildGridFromMatches(allMatches, pieces, rows, cols);
            double initialCost = evaluateGrid(initialGrid, rows, cols);
            System.out.println("Initial grid cost: " + String.format("%.2f", initialCost));
            System.out.println("Phase 3: Local optimization...");
            OrientedPiece[][] localGrid = localOptimization(initialGrid, pieces, rows, cols);
            double localCost = evaluateGrid(localGrid, rows, cols);
            System.out.println("After local optimization: " + String.format("%.2f", localCost));
            System.out.println("Phase 4: Global optimization with annealing...");
            OrientedPiece[][] finalGrid = globalSearchWithRotation(localGrid, pieces, rows, cols, localCost);

            return buildLayoutFromGrid(finalGrid);
        }

        static class RotationMatch implements Comparable<RotationMatch> {
            int piece1, piece2;
            int rot1, rot2;  
            EdgeDir edge1, edge2;
            double score;

            RotationMatch(int p1, int r1, EdgeDir e1, int p2, int r2, EdgeDir e2, double s) {
                this.piece1 = p1;
                this.rot1 = r1;
                this.edge1 = e1;
                this.piece2 = p2;
                this.rot2 = r2;
                this.edge2 = e2;
                this.score = s;
            }

            public int compareTo(RotationMatch o) {
                return Double.compare(this.score, o.score);
            }
        }

        private static List<RotationMatch> findBestRotationMatches(List<Piece> pieces) {
            List<RotationMatch> allMatches = new ArrayList<>();

            for (int i = 0; i < pieces.size(); i++) {
                for (int j = i + 1; j < pieces.size(); j++) {
                    for (int rot1 = 0; rot1 < 4; rot1++) {
                        OrientedPiece p1 = pieces.get(i).orientations[rot1];
                        for (int rot2 = 0; rot2 < 4; rot2++) {
                            OrientedPiece p2 = pieces.get(j).orientations[rot2];
                            // Test all edge combinations
                            for (EdgeDir e1 : EdgeDir.values()) {
                                EdgeDir e2 = getComplement(e1);

                                double score = EdgeFeature.mse(
                                    getEdge(p1, e1),
                                    getEdge(p2, e2)
                                );

                                allMatches.add(new RotationMatch(i, rot1, e1, j, rot2, e2, score));
                            }
                        }
                    }
                }
            }

            Collections.sort(allMatches);

            System.out.println("Tested " + allMatches.size() + " rotation combinations");
            System.out.println("Top 10 best matches:");
            for (int i = 0; i < Math.min(10, allMatches.size()); i++) {
                RotationMatch m = allMatches.get(i);
                System.out.println(String.format("  P%d(R%d°)-%s <-> P%d(R%d°)-%s = %.2f",
                    m.piece1, m.rot1*90, m.edge1, m.piece2, m.rot2*90, m.edge2, m.score));
            }

            return allMatches;
        }

        private static OrientedPiece[][] buildGridFromMatches(List<RotationMatch> matches,
                                                               List<Piece> pieces, int rows, int cols) {
            int n = pieces.size();
            OrientedPiece[][] grid = new OrientedPiece[rows][cols];
            int[] bestRotation = new int[n];  
            boolean[] placed = new boolean[n];
            Map<String, Boolean> usedEdges = new HashMap<>();
            Map<Integer, List<RotationMatch>> connections = new HashMap<>();
            for (int i = 0; i < n; i++) {
                connections.put(i, new ArrayList<>());
            }
            double THRESHOLD = 30.0;  // Only accept good matches
            for (RotationMatch m : matches) {
                if (m.score > THRESHOLD) break;

                String key1 = m.piece1 + "_" + m.rot1 + "_" + m.edge1;
                String key2 = m.piece2 + "_" + m.rot2 + "_" + m.edge2;

                if (usedEdges.containsKey(key1) || usedEdges.containsKey(key2)) continue;

                connections.get(m.piece1).add(m);
                connections.get(m.piece2).add(m);
                usedEdges.put(key1, true);
                usedEdges.put(key2, true);
                bestRotation[m.piece1] = m.rot1;
                bestRotation[m.piece2] = m.rot2;

                System.out.println(String.format("Good match: P%d(R%d°)-%s <-> P%d(R%d°)-%s = %.2f",
                    m.piece1, m.rot1*90, m.edge1, m.piece2, m.rot2*90, m.edge2, m.score));
            }

            // Place pieces greedily using local search
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double bestCost = Double.POSITIVE_INFINITY;
                    int bestPiece = -1;
                    int bestRot = 0;

                    for (int pi = 0; pi < n; pi++) {
                        if (placed[pi]) continue;

                        // Try the best rotation found for this piece, plus neighbors
                        int[] rotationsToTry = {bestRotation[pi], (bestRotation[pi]+1)%4,
                                                (bestRotation[pi]+3)%4, (bestRotation[pi]+2)%4};

                        for (int rot : rotationsToTry) {
                            OrientedPiece op = pieces.get(pi).orientations[rot];
                            double cost = localCompatibility(grid, rows, cols, r, c, op);

                            if (cost < bestCost) {
                                bestCost = cost;
                                bestPiece = pi;
                                bestRot = rot;
                            }
                        }
                    }

                    if (bestPiece != -1) {
                        grid[r][c] = pieces.get(bestPiece).orientations[bestRot];
                        placed[bestPiece] = true;
                        System.out.println("Placed P" + bestPiece + "(R" + (bestRot*90) +
                                         "°) at [" + r + "," + c + "] cost=" +
                                         String.format("%.2f", bestCost));
                    }
                }
            }

            return grid;
        }

        private static OrientedPiece[][] localOptimization(OrientedPiece[][] grid, List<Piece> pieces,
                                                           int rows, int cols) {
            boolean improved = true;
            int iterations = 0;

            while (improved && iterations < 20) {
                improved = false;
                iterations++;

                // Try swapping adjacent pieces
                for (int r1 = 0; r1 < rows; r1++) {
                    for (int c1 = 0; c1 < cols; c1++) {
                        if (grid[r1][c1] == null) continue;

                        // Try swapping with neighbors
                        int[][] neighbors = {{r1-1,c1}, {r1+1,c1}, {r1,c1-1}, {r1,c1+1}};

                        for (int[] nb : neighbors) {
                            int r2 = nb[0];
                            int c2 = nb[1];

                            if (r2 < 0 || r2 >= rows || c2 < 0 || c2 >= cols) continue;
                            if (grid[r2][c2] == null) continue;

                            double costBefore = localCompatibility(grid, rows, cols, r1, c1, grid[r1][c1]) +
                                              localCompatibility(grid, rows, cols, r2, c2, grid[r2][c2]);

                            OrientedPiece temp = grid[r1][c1];
                            grid[r1][c1] = grid[r2][c2];
                            grid[r2][c2] = temp;

                            double costAfter = localCompatibility(grid, rows, cols, r1, c1, grid[r1][c1]) +
                                             localCompatibility(grid, rows, cols, r2, c2, grid[r2][c2]);

                            if (costAfter < costBefore - 0.1) {
                                improved = true;
                                System.out.println("Local swap improved: [" + r1 + "," + c1 + "] <-> [" +
                                                 r2 + "," + c2 + "]");
                            } else {
                                OrientedPiece temp2 = grid[r1][c1];
                                grid[r1][c1] = grid[r2][c2];
                                grid[r2][c2] = temp2;
                            }
                        }
                    }
                }
            }

            System.out.println("Local optimization: " + iterations + " iterations");
            return grid;
        }

        private static OrientedPiece[][] localSearchWithRotation(List<Piece> pieces, int rows, int cols) {
            OrientedPiece[][] grid = new OrientedPiece[rows][cols];
            boolean[] used = new boolean[pieces.size()];

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (isTimedOut()) return grid;

                    // Find best piece AND rotation for this position
                    double bestCost = Double.POSITIVE_INFINITY;
                    int bestPiece = -1;
                    int bestOrientation = 0;

                    for (int pi = 0; pi < pieces.size(); pi++) {
                        if (used[pi]) continue;
                        for (int ori = 0; ori < 4; ori++) {
                            OrientedPiece op = pieces.get(pi).orientations[ori];
                            double cost = localCompatibility(grid, rows, cols, r, c, op);

                            if (cost < bestCost) {
                                bestCost = cost;
                                bestPiece = pi;
                                bestOrientation = ori;
                            }
                        }
                    }

                    if (bestPiece != -1) {
                        grid[r][c] = pieces.get(bestPiece).orientations[bestOrientation];
                        used[bestPiece] = true;
                        System.out.println("Placed piece " + bestPiece + " (rot=" + (bestOrientation*90) +
                                         "°) at [" + r + "," + c + "] cost=" + String.format("%.2f", bestCost));
                    }
                }
            }

            return grid;
        }

        private static OrientedPiece[][] globalSearchWithRotation(OrientedPiece[][] initialGrid,
                                                                   List<Piece> pieces, int rows, int cols,
                                                                   double initialCost) {
            OrientedPiece[][] currentGrid = copyGrid(initialGrid);
            OrientedPiece[][] bestGrid = copyGrid(initialGrid);
            double currentCost = initialCost;
            double bestCost = initialCost;

            double temperature = 100.0;  
            double coolingRate = 0.995;
            int iterations = 0;
            int maxIterations = 50000;  
            Random rng = new Random(42);

            System.out.println("Starting annealing with T=" + temperature + ", max_iter=" + maxIterations);

            while (temperature > 0.1 && iterations < maxIterations && !isTimedOut()) {
                iterations++;

                // Random operation: swap OR rotate
                if (rng.nextDouble() < 0.7) {
                    int r1 = rng.nextInt(rows);
                    int c1 = rng.nextInt(cols);
                    int r2 = rng.nextInt(rows);
                    int c2 = rng.nextInt(cols);

                    if (r1 == r2 && c1 == c2) continue;
                    if (currentGrid[r1][c1] == null || currentGrid[r2][c2] == null) continue;

                    OrientedPiece temp = currentGrid[r1][c1];
                    currentGrid[r1][c1] = currentGrid[r2][c2];
                    currentGrid[r2][c2] = temp;

                    double newCost = evaluateGrid(currentGrid, rows, cols);
                    double delta = newCost - currentCost;

                    if (delta < 0 || Math.exp(-delta / temperature) > rng.nextDouble()) {
                        currentCost = newCost;
                        if (currentCost < bestCost) {
                            bestCost = currentCost;
                            bestGrid = copyGrid(currentGrid);
                            System.out.println("Iter " + iterations + ": NEW BEST by swap = " +
                                             String.format("%.2f", bestCost) + " (T=" +
                                             String.format("%.2f", temperature) + ")");
                        }
                    } else {
                        OrientedPiece temp2 = currentGrid[r1][c1];
                        currentGrid[r1][c1] = currentGrid[r2][c2];
                        currentGrid[r2][c2] = temp2;
                    }
                } else {
                    int r = rng.nextInt(rows);
                    int c = rng.nextInt(cols);

                    if (currentGrid[r][c] == null) continue;

                    OrientedPiece old = currentGrid[r][c];
                    Piece parent = old.parent;
                    int oldRot = old.rotation / 90;
                    int newRot = rng.nextInt(4);

                    if (oldRot == newRot) continue;
                    currentGrid[r][c] = parent.orientations[newRot];

                    double newCost = evaluateGrid(currentGrid, rows, cols);
                    double delta = newCost - currentCost;

                    if (delta < 0 || Math.exp(-delta / temperature) > rng.nextDouble()) {
                        currentCost = newCost;
                        if (currentCost < bestCost) {
                            bestCost = currentCost;
                            bestGrid = copyGrid(currentGrid);
                            System.out.println("Iter " + iterations + ": NEW BEST by rotation = " +
                                             String.format("%.2f", bestCost) + " (T=" +
                                             String.format("%.2f", temperature) + ")");
                        }
                    } else {
                        currentGrid[r][c] = old;
                    }
                }

                temperature *= coolingRate;

                if (iterations % 5000 == 0) {
                    System.out.println("Progress: iter=" + iterations + ", current=" +
                                     String.format("%.2f", currentCost) + ", best=" +
                                     String.format("%.2f", bestCost) + ", T=" +
                                     String.format("%.2f", temperature));
                }
            }

            System.out.println("Annealing complete: " + iterations + " iterations, final cost=" +
                             String.format("%.2f", bestCost));
            return bestGrid;
        }

        enum EdgeDir { TOP, BOTTOM, LEFT, RIGHT }

        static class EdgeMatch implements Comparable<EdgeMatch> {
            int piece1, piece2;
            EdgeDir edge1, edge2;
            double score;

            EdgeMatch(int p1, EdgeDir e1, int p2, EdgeDir e2, double s) {
                this.piece1 = p1;
                this.edge1 = e1;
                this.piece2 = p2;
                this.edge2 = e2;
                this.score = s;
            }

            public int compareTo(EdgeMatch o) {
                return Double.compare(this.score, o.score);
            }
        }

        static class PlacementEdge {
            int fromPiece, toPiece;
            EdgeDir direction;
            double score;

            PlacementEdge(int from, int to, EdgeDir dir, double score) {
                this.fromPiece = from;
                this.toPiece = to;
                this.direction = dir;
                this.score = score;
            }
        }

        static class GridPos {
            int row, col;
            GridPos(int r, int c) { this.row = r; this.col = c; }
        }

        private static EdgeDir getComplement(EdgeDir e) {
            switch(e) {
                case TOP: return EdgeDir.BOTTOM;
                case BOTTOM: return EdgeDir.TOP;
                case LEFT: return EdgeDir.RIGHT;
                case RIGHT: return EdgeDir.LEFT;
                default: return null;
            }
        }

        private static List<EdgeMatch> performEdgeMatching(List<Piece> pieces) {
            List<EdgeMatch> matches = new ArrayList<>();
            System.out.println("Computing edge matches...");

            for (int i = 0; i < pieces.size(); i++) {
                for (int j = i + 1; j < pieces.size(); j++) {
                    OrientedPiece p1 = pieces.get(i).orientations[0];
                    OrientedPiece p2 = pieces.get(j).orientations[0];

                    for (EdgeDir e1 : EdgeDir.values()) {
                        EdgeDir e2 = getComplement(e1);

                        double score = EdgeFeature.mse(
                            getEdge(p1, e1),
                            getEdge(p2, e2)
                        );

                        matches.add(new EdgeMatch(i, e1, j, e2, score));
                    }
                }
            }

            Collections.sort(matches);
            System.out.println("Generated " + matches.size() + " matches");
            System.out.println("Top 10 matches:");
            for (int i = 0; i < Math.min(10, matches.size()); i++) {
                EdgeMatch m = matches.get(i);
                System.out.println("  Piece " + m.piece1 + " " + m.edge1 + " <-> Piece " + m.piece2 + " " + m.edge2 + " = " + String.format("%.2f", m.score));
            }

            return matches;
        }

        private static EdgeFeature getEdge(OrientedPiece p, EdgeDir dir) {
            switch(dir) {
                case TOP: return p.edges[0];
                case RIGHT: return p.edges[1];
                case BOTTOM: return p.edges[2];
                case LEFT: return p.edges[3];
                default: return null;
            }
        }

        private static Map<Integer, Map<EdgeDir, PlacementEdge>> buildAdjacencyGraph(
                List<EdgeMatch> matches, int numPieces) {

            Map<Integer, Map<EdgeDir, PlacementEdge>> graph = new HashMap<>();
            for (int i = 0; i < numPieces; i++) {
                graph.put(i, new HashMap<>());
            }

            Set<String> used = new HashSet<>();
            double THRESHOLD = 25.0;  // Stricter threshold for better matches

            for (EdgeMatch m : matches) {
                if (m.score > THRESHOLD) break;

                String e1 = m.piece1 + "_" + m.edge1;
                String e2 = m.piece2 + "_" + m.edge2;

                if (used.contains(e1) || used.contains(e2)) continue;

                graph.get(m.piece1).put(m.edge1, new PlacementEdge(m.piece1, m.piece2, m.edge1, m.score));
                graph.get(m.piece2).put(m.edge2, new PlacementEdge(m.piece2, m.piece1, m.edge2, m.score));

                used.add(e1);
                used.add(e2);

                System.out.println("Connection: piece " + m.piece1 + " " + m.edge1 + " <-> piece " + m.piece2 + " " + m.edge2);
            }

            return graph;
        }

        private static Map<Integer, GridPos> computeLayoutFromGraph(
                Map<Integer, Map<EdgeDir, PlacementEdge>> graph, int numPieces) {

            Map<Integer, GridPos> layout = new HashMap<>();
            Queue<Integer> queue = new LinkedList<>();

            layout.put(0, new GridPos(0, 0));
            queue.add(0);

            while (!queue.isEmpty()) {
                int cur = queue.poll();
                GridPos curPos = layout.get(cur);

                for (Map.Entry<EdgeDir, PlacementEdge> entry : graph.get(cur).entrySet()) {
                    EdgeDir dir = entry.getKey();
                    int neighbor = entry.getValue().toPiece;

                    if (layout.containsKey(neighbor)) continue;

                    int nr = curPos.row, nc = curPos.col;
                    switch(dir) {
                        case TOP: nr--; break;
                        case BOTTOM: nr++; break;
                        case LEFT: nc--; break;
                        case RIGHT: nc++; break;
                    }

                    layout.put(neighbor, new GridPos(nr, nc));
                    queue.add(neighbor);
                }
            }

            // Normalize to start at (0,0)
            int minR = Integer.MAX_VALUE, minC = Integer.MAX_VALUE;
            for (GridPos gp : layout.values()) {
                minR = Math.min(minR, gp.row);
                minC = Math.min(minC, gp.col);
            }
            for (GridPos gp : layout.values()) {
                gp.row -= minR;
                gp.col -= minC;
            }

            System.out.println("\nFinal layout:");
            for (int i = 0; i < numPieces; i++) {
                GridPos gp = layout.get(i);
                if (gp != null) {
                    System.out.println("Piece " + i + " at [" + gp.row + "," + gp.col + "]");
                }
            }

            return layout;
        }

        private static Map<Integer, GridPos> fixOutOfBoundsPieces(Map<Integer, GridPos> layout, int numPieces) {
            // Determine CORRECT grid dimensions based on number of pieces
            int gridRows, gridCols;
            if (numPieces == 16) {
                gridRows = 4;
                gridCols = 4;
            } else if (numPieces == 20) {
                gridRows = 4;
                gridCols = 5;
            } else {
                // For other sizes, make a roughly square grid
                gridRows = (int) Math.sqrt(numPieces);
                gridCols = (int) Math.ceil((double) numPieces / gridRows);
            }

            System.out.println("\n=== COMPACTING LAYOUT TO PROPER GRID ===");
            System.out.println("Target grid size: " + gridRows + "x" + gridCols + " for " + numPieces + " pieces");

            // Build a map of which pieces have positions and which are missing
            boolean[][] occupied = new boolean[gridRows][gridCols];
            java.util.List<Integer> missingPieces = new java.util.ArrayList<>();
            java.util.List<Integer> outOfBoundsPieces = new java.util.ArrayList<>();

            for (int i = 0; i < numPieces; i++) {
                GridPos gp = layout.get(i);
                if (gp == null) {
                    missingPieces.add(i);
                } else if (gp.row < 0 || gp.row >= gridRows || gp.col < 0 || gp.col >= gridCols) {
                    outOfBoundsPieces.add(i);
                } else {
                    // Check for duplicate positions
                    if (occupied[gp.row][gp.col]) {
                        System.out.println("WARNING: Duplicate position [" + gp.row + "," + gp.col + "] - piece " + i + " needs relocation");
                        outOfBoundsPieces.add(i);
                    } else {
                        occupied[gp.row][gp.col] = true;
                    }
                }
            }

            // Combine missing and out-of-bounds pieces
            java.util.List<Integer> piecesToPlace = new java.util.ArrayList<>();
            piecesToPlace.addAll(missingPieces);
            piecesToPlace.addAll(outOfBoundsPieces);

            if (piecesToPlace.isEmpty()) {
                System.out.println("All pieces within bounds!");
                return layout;
            }

            System.out.println("Pieces to relocate: " + piecesToPlace);

            // Place them in blank spots
            int fixedCount = 0;
            for (int pieceId : piecesToPlace) {
                boolean placed = false;
                for (int r = 0; r < gridRows && !placed; r++) {
                    for (int c = 0; c < gridCols && !placed; c++) {
                        if (!occupied[r][c]) {
                            layout.put(pieceId, new GridPos(r, c));
                            occupied[r][c] = true;
                            placed = true;
                            fixedCount++;
                            System.out.println("Placed piece " + pieceId + " at [" + r + "," + c + "]");
                        }
                    }
                }

                if (!placed) {
                    System.out.println("ERROR: Could not find blank spot for piece " + pieceId);
                }
            }

            System.out.println("Relocated " + fixedCount + " pieces\n");

            return layout;
        }

        private static Map<Integer, GridPos> optimizePlacement(Map<Integer, GridPos> layout, List<Piece> pieces) {
            System.out.println("\n=== OPTIMIZING PLACEMENT ===");

            // Determine grid dimensions
            int gridRows, gridCols;
            if (pieces.size() == 16) {
                gridRows = 4;
                gridCols = 4;
            } else if (pieces.size() == 20) {
                gridRows = 4;
                gridCols = 5;
            } else {
                gridRows = (int) Math.sqrt(pieces.size());
                gridCols = (int) Math.ceil((double) pieces.size() / gridRows);
            }

            // Create a grid mapping position to piece ID
            int[][] grid = new int[gridRows][gridCols];
            for (int r = 0; r < gridRows; r++) {
                for (int c = 0; c < gridCols; c++) {
                    grid[r][c] = -1;
                }
            }
            for (int pieceId = 0; pieceId < pieces.size(); pieceId++) {
                GridPos gp = layout.get(pieceId);
                if (gp != null && gp.row >= 0 && gp.row < gridRows && gp.col >= 0 && gp.col < gridCols) {
                    grid[gp.row][gp.col] = pieceId;
                }
            }

            // Calculate initial cost
            double initialCost = computeGridCost(grid, pieces, gridRows, gridCols);
            System.out.println("Initial edge cost: " + String.format("%.2f", initialCost));

            // Run multiple optimization passes
            int improvements = 0;
            int totalSwaps = 0;
            for (int pass = 0; pass < 10; pass++) {  
                boolean improved = false;

                for (int r1 = 0; r1 < gridRows; r1++) {
                    for (int c1 = 0; c1 < gridCols; c1++) {
                        for (int r2 = 0; r2 < gridRows; r2++) {
                            for (int c2 = 0; c2 < gridCols; c2++) {
                                if (r1 == r2 && c1 == c2) continue;

                                int piece1 = grid[r1][c1];
                                int piece2 = grid[r2][c2];
                                if (piece1 < 0 || piece2 < 0) continue;

                                double localCostBefore = computeLocalCost(grid, pieces, gridRows, gridCols, r1, c1) +
                                                        computeLocalCost(grid, pieces, gridRows, gridCols, r2, c2);

                                grid[r1][c1] = piece2;
                                grid[r2][c2] = piece1;

                                double localCostAfter = computeLocalCost(grid, pieces, gridRows, gridCols, r1, c1) +
                                                       computeLocalCost(grid, pieces, gridRows, gridCols, r2, c2);

                                if (localCostAfter < localCostBefore - 0.01) {  // Accept if improvement
                                    // Keep the swap
                                    improved = true;
                                    improvements++;
                                    totalSwaps++;
                                    System.out.println("Pass " + pass + ": Swapped [" + r1 + "," + c1 + "] <-> [" + r2 + "," + c2 + "], improvement: " + String.format("%.2f", localCostBefore - localCostAfter));
                                } else {
                                    // Revert swap
                                    grid[r1][c1] = piece1;
                                    grid[r2][c2] = piece2;
                                }
                            }
                        }
                    }
                }
                if (!improved) break;  

                // Recalculate total cost after each pass
                initialCost = computeGridCost(grid, pieces, gridRows, gridCols);
                System.out.println("Pass " + pass + " complete. Current cost: " + String.format("%.2f", initialCost));
            }

            System.out.println("Optimization complete: " + improvements + " improvements, final cost: " + String.format("%.2f", initialCost));

            // Update layout from optimized grid
            for (int r = 0; r < gridRows; r++) {
                for (int c = 0; c < gridCols; c++) {
                    int pieceId = grid[r][c];
                    if (pieceId >= 0) {
                        layout.put(pieceId, new GridPos(r, c));
                    }
                }
            }

            return layout;
        }

        private static double computeLocalCost(int[][] grid, List<Piece> pieces, int rows, int cols, int r, int c) {
            double totalCost = 0;
            int edgeCount = 0;

            int pieceId = grid[r][c];
            if (pieceId < 0) return 0;

            Piece piece = pieces.get(pieceId);

            // RIGHT
            if (c + 1 < cols && grid[r][c + 1] >= 0) {
                Piece rightPiece = pieces.get(grid[r][c + 1]);
                totalCost += EdgeFeature.mse(piece.orientations[0].edges[1], rightPiece.orientations[0].edges[3]);
                edgeCount++;
            }
            // BOTTOM
            if (r + 1 < rows && grid[r + 1][c] >= 0) {
                Piece bottomPiece = pieces.get(grid[r + 1][c]);
                totalCost += EdgeFeature.mse(piece.orientations[0].edges[2], bottomPiece.orientations[0].edges[0]);
                edgeCount++;
            }
            // LEFT
            if (c - 1 >= 0 && grid[r][c - 1] >= 0) {
                Piece leftPiece = pieces.get(grid[r][c - 1]);
                totalCost += EdgeFeature.mse(piece.orientations[0].edges[3], leftPiece.orientations[0].edges[1]);
                edgeCount++;
            }
            // TOP
            if (r - 1 >= 0 && grid[r - 1][c] >= 0) {
                Piece topPiece = pieces.get(grid[r - 1][c]);
                totalCost += EdgeFeature.mse(piece.orientations[0].edges[0], topPiece.orientations[0].edges[2]);
                edgeCount++;
            }

            return edgeCount > 0 ? totalCost / edgeCount : 0;
        }

        private static double computeGridCost(int[][] grid, List<Piece> pieces, int rows, int cols) {
            double totalCost = 0;
            int edgeCount = 0;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int pieceId = grid[r][c];
                    if (pieceId < 0) continue;

                    Piece piece = pieces.get(pieceId);

                    // Check RIGHT neighbor
                    if (c + 1 < cols && grid[r][c + 1] >= 0) {
                        Piece rightPiece = pieces.get(grid[r][c + 1]);
                        double cost = EdgeFeature.mse(piece.orientations[0].edges[1], rightPiece.orientations[0].edges[3]);
                        totalCost += cost;
                        edgeCount++;
                    }

                    // Check BOTTOM neighbor
                    if (r + 1 < rows && grid[r + 1][c] >= 0) {
                        Piece bottomPiece = pieces.get(grid[r + 1][c]);
                        double cost = EdgeFeature.mse(piece.orientations[0].edges[2], bottomPiece.orientations[0].edges[0]);
                        totalCost += cost;
                        edgeCount++;
                    }
                }
            }

            return edgeCount > 0 ? totalCost / edgeCount : Double.MAX_VALUE;
        }

        private static OrientedPiece[][] convertLayoutToGrid(Map<Integer, GridPos> layout, List<Piece> pieces) {
            int maxR = 0, maxC = 0;
            for (GridPos gp : layout.values()) {
                maxR = Math.max(maxR, gp.row);
                maxC = Math.max(maxC, gp.col);
            }

            OrientedPiece[][] grid = new OrientedPiece[maxR + 1][maxC + 1];
            for (int i = 0; i < pieces.size(); i++) {
                GridPos gp = layout.get(i);
                if (gp != null) {
                    grid[gp.row][gp.col] = pieces.get(i).orientations[0];
                }
            }

            return grid;
        }

        // LOCAL SEARCH: Greedy construction
        private static OrientedPiece[][] localSearch(List<Piece> pieces, int rows, int cols) {
            OrientedPiece[][] grid = new OrientedPiece[rows][cols];
            boolean[] used = new boolean[pieces.size()];

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (isTimedOut()) return grid;

                    // Find best piece for this position
                    double bestCost = Double.POSITIVE_INFINITY;
                    int bestPiece = -1;
                    int bestOrientation = 0;

                    for (int pi = 0; pi < pieces.size(); pi++) {
                        if (used[pi]) continue;

                        for (int ori = 0; ori < 4; ori++) {
                            OrientedPiece op = pieces.get(pi).orientations[ori];
                            double cost = localCompatibility(grid, rows, cols, r, c, op);

                            if (cost < bestCost) {
                                bestCost = cost;
                                bestPiece = pi;
                                bestOrientation = ori;
                            }
                        }
                    }

                    if (bestPiece != -1) {
                        grid[r][c] = pieces.get(bestPiece).orientations[bestOrientation];
                        used[bestPiece] = true;
                        System.out.println("Placed piece " + bestPiece + " at [" + r + "," + c + "] with cost " + bestCost);
                    }
                }
            }

            return grid;
        }

        // GLOBAL SEARCH: Simulated annealing for optimization
        private static OrientedPiece[][] globalSearch(OrientedPiece[][] initialGrid, List<Piece> pieces,
                                                       int rows, int cols, double initialCost) {
            OrientedPiece[][] currentGrid = copyGrid(initialGrid);
            OrientedPiece[][] bestGrid = copyGrid(initialGrid);
            double currentCost = initialCost;
            double bestCost = initialCost;

            double temperature = 1000.0;
            double coolingRate = 0.9995;
            int iterations = 0;
            int maxIterations = 100000;
            Random rng = new Random();

            while (temperature > 0.1 && iterations < maxIterations && !isTimedOut()) {
                iterations++;

                // Try swapping two random pieces
                int r1 = rng.nextInt(rows);
                int c1 = rng.nextInt(cols);
                int r2 = rng.nextInt(rows);
                int c2 = rng.nextInt(cols);

                if (r1 == r2 && c1 == c2) continue;

                // Swap
                OrientedPiece temp = currentGrid[r1][c1];
                currentGrid[r1][c1] = currentGrid[r2][c2];
                currentGrid[r2][c2] = temp;

                double newCost = evaluateGrid(currentGrid, rows, cols);
                double delta = newCost - currentCost;

                // Accept if better, or with probability based on temperature
                if (delta < 0 || Math.exp(-delta / temperature) > rng.nextDouble()) {
                    currentCost = newCost;

                    if (currentCost < bestCost) {
                        bestCost = currentCost;
                        bestGrid = copyGrid(currentGrid);
                        System.out.println("Iteration " + iterations + ": New best cost = " + bestCost + " (T=" + temperature + ")");
                    }
                } else {
                    // Reject - swap back
                    OrientedPiece temp2 = currentGrid[r1][c1];
                    currentGrid[r1][c1] = currentGrid[r2][c2];
                    currentGrid[r2][c2] = temp2;
                }

                temperature *= coolingRate;

                if (iterations % 10000 == 0) {
                    System.out.println("Iteration " + iterations + ": cost = " + currentCost + ", best = " + bestCost + ", T = " + temperature);
                }
            }

            System.out.println("Global search completed: " + iterations + " iterations");
            return bestGrid;
        }

        private static double evaluateGrid(OrientedPiece[][] grid, int rows, int cols) {
            double totalCost = 0.0;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (grid[r][c] == null) continue;

                    // Check right neighbor
                    if (c < cols - 1 && grid[r][c + 1] != null) {
                        totalCost += EdgeFeature.mse(grid[r][c].edges[1], grid[r][c + 1].edges[3]);
                    }

                    // Check bottom neighbor
                    if (r < rows - 1 && grid[r + 1][c] != null) {
                        totalCost += EdgeFeature.mse(grid[r][c].edges[2], grid[r + 1][c].edges[0]);
                    }
                }
            }

            return totalCost;
        }

        private static OrientedPiece[][] copyGrid(OrientedPiece[][] grid) {
            int rows = grid.length;
            int cols = grid[0].length;
            OrientedPiece[][] ng = new OrientedPiece[rows][cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(grid[i], 0, ng[i], 0, cols);
            }
            return ng;
        }

        private static double localCompatibility(OrientedPiece[][] grid, int rows, int cols, int r, int c, OrientedPiece op) {
            double cost = 0.0;
            int neighbors = 0;

            if (c > 0 && grid[r][c - 1] != null) {
                cost += EdgeFeature.mse(grid[r][c - 1].edges[1], op.edges[3]);
                neighbors++;
            }
            if (r > 0 && grid[r - 1][c] != null) {
                cost += EdgeFeature.mse(grid[r - 1][c].edges[2], op.edges[0]);
                neighbors++;
            }
            return (neighbors == 0) ? 1.0 : cost;
        }

        private static PuzzleLayout simpleFallbackLayout(List<Piece> pieces) {
            int n = pieces.size();
            int dim = (int) Math.round(Math.sqrt(n));
            int rows = dim;
            int cols = dim;
            OrientedPiece[][] grid = new OrientedPiece[rows][cols];

            for (int i = 0; i < n; i++) {
                grid[i / cols][i % cols] = pieces.get(i).orientations[0];
            }
            return buildLayoutFromGrid(grid);
        }

        private static PuzzleLayout buildLayoutFromGrid(OrientedPiece[][] grid) {
            int rows = grid.length;
            int cols = grid[0].length;

            // Calculate average piece size 
            int totalW = 0, totalH = 0, count = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (grid[r][c] != null) {
                        Piece p = grid[r][c].parent;
                        totalW += p.original.getWidth();
                        totalH += p.original.getHeight();
                        count++;
                    }
                }
            }

            int avgPieceW = count > 0 ? totalW / count : 200;
            int avgPieceH = count > 0 ? totalH / count : 200;

            PuzzleLayout layout = new PuzzleLayout();
            layout.canvasWidth = cols * avgPieceW;
            layout.canvasHeight = rows * avgPieceH;

            // Store puzzle dimensions for centering
            layout.puzzleWidth = cols * avgPieceW;
            layout.puzzleHeight = rows * avgPieceH;

            // Place pieces at their grid positions using original sizes
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (grid[r][c] != null) {
                        Piece p = grid[r][c].parent;
                        // Calculate target position based on grid location
                        int targetX = c * avgPieceW;
                        int targetY = r * avgPieceH;

                        layout.placedPieces.add(new PlacedPiece(p, grid[r][c].rotation, targetX, targetY));
                    }
                }
            }

            System.out.println("Final canvas: " + layout.canvasWidth + "x" + layout.canvasHeight);
            System.out.println("Using original piece sizes (avg: " + avgPieceW + "x" + avgPieceH + ")");
            return layout;
        }

        private static BufferedImage resizeImage(BufferedImage src, int targetW, int targetH) {
            BufferedImage dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Sample the average color from the edge pixels of source image to use as background
            int sampleCount = 0;
            long rSum = 0, gSum = 0, bSum = 0;
            int w = src.getWidth();
            int h = src.getHeight();

            // Sample edges of source image
            for (int x = 0; x < w; x++) {
                int rgb1 = src.getRGB(x, 0);
                int rgb2 = src.getRGB(x, h - 1);
                rSum += ((rgb1 >> 16) & 0xFF) + ((rgb2 >> 16) & 0xFF);
                gSum += ((rgb1 >> 8) & 0xFF) + ((rgb2 >> 8) & 0xFF);
                bSum += (rgb1 & 0xFF) + (rgb2 & 0xFF);
                sampleCount += 2;
            }
            for (int y = 0; y < h; y++) {
                int rgb1 = src.getRGB(0, y);
                int rgb2 = src.getRGB(w - 1, y);
                rSum += ((rgb1 >> 16) & 0xFF) + ((rgb2 >> 16) & 0xFF);
                gSum += ((rgb1 >> 8) & 0xFF) + ((rgb2 >> 8) & 0xFF);
                bSum += (rgb1 & 0xFF) + (rgb2 & 0xFF);
                sampleCount += 2;
            }

            Color avgColor = new Color((int)(rSum / sampleCount), (int)(gSum / sampleCount), (int)(bSum / sampleCount));
            g2.setColor(avgColor);
            g2.fillRect(0, 0, targetW, targetH);

            // Scale to fit
            g2.drawImage(src, 0, 0, targetW, targetH, null);
            g2.dispose();
            return dst;
        }

        public static void scrambleLayout(PuzzleLayout layout, int originalW, int originalH) {
            Random rng = new Random();
            int canvasW = Math.max(originalW, layout.canvasWidth);
            int canvasH = Math.max(originalH, layout.canvasHeight);

            for (PlacedPiece pp : layout.placedPieces) {
                int pieceW = pp.piece.original.getWidth();
                int pieceH = pp.piece.original.getHeight();
                int maxX = Math.max(0, canvasW - pieceW);
                int maxY = Math.max(0, canvasH - pieceH);
                pp.startX = rng.nextInt(maxX + 1);
                pp.startY = rng.nextInt(maxY + 1);
                pp.startRotation = rng.nextInt(361) - 180;
                pp.rotation = 0;
            }
        }
    }

    static class AnimationWindow extends JFrame {
        public static void show(BufferedImage originalImage, PuzzleLayout layout) {
            SwingUtilities.invokeLater(() -> {
                AnimationWindow win = new AnimationWindow(originalImage, layout);
                win.setVisible(true);
            });
        }

        public AnimationWindow(BufferedImage originalImage, PuzzleLayout layout) {
            setTitle("PuzzleSolver");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            int canvasW = Math.max(originalImage.getWidth(), layout.canvasWidth);
            int canvasH = Math.max(originalImage.getHeight(), layout.canvasHeight);
            AnimationPanel panel = new AnimationPanel(originalImage, layout, canvasW, canvasH);
            getContentPane().add(panel);
            pack();
            setLocationRelativeTo(null);
        }
    }

    static class AnimationPanel extends JPanel {
        private final BufferedImage originalImage;
        private final PuzzleLayout layout;
        private final int canvasW, canvasH;
        private double progress = 0.0;
        private Timer timer;
        private static final int DELAY_SECONDS = 1;  
        private static final int DELAY_FRAMES = DELAY_SECONDS * ANIMATION_FPS;

        public AnimationPanel(BufferedImage originalImage, PuzzleLayout layout, int canvasW, int canvasH) {
            this.originalImage = originalImage;
            this.layout = layout;
            this.canvasW = canvasW;
            this.canvasH = canvasH;
            setPreferredSize(new Dimension(canvasW, canvasH));
            setBackground(Color.BLACK);

            timer = new Timer(1000 / ANIMATION_FPS, new ActionListener() {
                private int frameCount = 0;

                @Override
                public void actionPerformed(ActionEvent e) {
                    frameCount++;

                    if (frameCount <= DELAY_FRAMES) {
                        progress = 0.0;
                    } else {
                        progress = Math.min(1.0, (frameCount - DELAY_FRAMES) * 1.0 / ANIMATION_FRAMES);
                    }

                    repaint();
                    if (progress >= 1.0) timer.stop();
                }
            });
            timer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Calculate offset to center the solved puzzle on canvas
            int offsetX = (canvasW - layout.puzzleWidth) / 2;
            int offsetY = (canvasH - layout.puzzleHeight) / 2;

            // Animate pieces from original positions to solved positions
            double t = easeInOut(progress);

            for (PlacedPiece pp : layout.placedPieces) {
                // Target positions are centered on canvas
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
        }

        private double lerp(double a, double b, double t) {
            return a + t * (b - a);
        }

        private double easeInOut(double t) {
            return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        }
    }
}
