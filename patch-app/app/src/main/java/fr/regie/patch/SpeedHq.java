package fr.regie.patch;

/**
 * Décodeur SpeedHQ (SHQ0 à SHQ9), le codec des flux NDI « pleine » bande
 * comme de leur aperçu basse qualité. Transcription en Java du décodeur de
 * FFmpeg (libavcodec/speedhqdec.c, Steinar H. Gunderson, LGPL 2.1) : même
 * lecture des blocs, mêmes tables. Seule l'IDCT diffère (flottante ici), d'où
 * un écart de l'ordre d'un niveau sur 255 avec FFmpeg.
 *
 * Sortie : une image ARGB, alpha ignoré (on n'affiche qu'un aperçu).
 */
public class SpeedHq {

    /* Tables de FFmpeg : speedhq.c, mpeg12data.c, mathtables.c. */

    private static final int[][] AC_VLC = {
        {0x0001,  2}, {0x0003,  3}, {0x000E,  4}, {0x0007,  5},
        {0x0017,  5}, {0x0028,  6}, {0x0008,  6}, {0x006F,  7},
        {0x001F,  7}, {0x00C4,  8}, {0x0044,  8}, {0x005F,  8},
        {0x00DF,  8}, {0x007F,  8}, {0x00FF,  8}, {0x3E00, 14},
        {0x1E00, 14}, {0x2E00, 14}, {0x0E00, 14}, {0x3600, 14},
        {0x1600, 14}, {0x2600, 14}, {0x0600, 14}, {0x3A00, 14},
        {0x1A00, 14}, {0x2A00, 14}, {0x0A00, 14}, {0x3200, 14},
        {0x1200, 14}, {0x2200, 14}, {0x0200, 14}, {0x0C00, 15},
        {0x7400, 15}, {0x3400, 15}, {0x5400, 15}, {0x1400, 15},
        {0x6400, 15}, {0x2400, 15}, {0x4400, 15}, {0x0400, 15},
        {0x0002,  3}, {0x000C,  5}, {0x004F,  7}, {0x00E4,  8},
        {0x0004,  8}, {0x0D00, 13}, {0x1500, 13}, {0x7C00, 15},
        {0x3C00, 15}, {0x5C00, 15}, {0x1C00, 15}, {0x6C00, 15},
        {0x2C00, 15}, {0x4C00, 15}, {0xC800, 16}, {0x4800, 16},
        {0x8800, 16}, {0x0800, 16}, {0x0300, 13}, {0x1D00, 13},
        {0x0014,  5}, {0x0070,  7}, {0x003F,  8}, {0x00C0, 10},
        {0x0500, 13}, {0x0180, 12}, {0x0280, 12}, {0x0C80, 12},
        {0x0080, 12}, {0x0B00, 13}, {0x1300, 13}, {0x001C,  5},
        {0x0064,  8}, {0x0380, 12}, {0x1900, 13}, {0x0D80, 12},
        {0x0018,  6}, {0x00BF,  8}, {0x0480, 12}, {0x0B80, 12},
        {0x0038,  6}, {0x0040,  9}, {0x0900, 13}, {0x0030,  7},
        {0x0780, 12}, {0x2800, 16}, {0x0010,  7}, {0x0A80, 12},
        {0x0050,  7}, {0x0880, 12}, {0x000F,  7}, {0x1100, 13},
        {0x002F,  7}, {0x0100, 13}, {0x0084,  8}, {0x5800, 16},
        {0x00A4,  8}, {0x9800, 16}, {0x0024,  8}, {0x1800, 16},
        {0x0140,  9}, {0xE800, 16}, {0x01C0,  9}, {0x6800, 16},
        {0x02C0, 10}, {0xA800, 16}, {0x0F80, 12}, {0x0580, 12},
        {0x0980, 12}, {0x0E80, 12}, {0x0680, 12}, {0x1F00, 13},
        {0x0F00, 13}, {0x1700, 13}, {0x0700, 13}, {0x1B00, 13},
        {0xF800, 16}, {0x7800, 16}, {0xB800, 16}, {0x3800, 16},
        {0xD800, 16},
        {0x0020,  6},   // échappement
        {0x0006,  4}    // fin de bloc
    };

    private static final int[] AC_NIVEAU = {
         1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40,  1,  2,  3,  4,  5,  6,  7,  8,
         9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,  1,  2,  3,  4,
         5,  6,  7,  8,  9, 10, 11,  1,  2,  3,  4,  5,  1,  2,  3,  4,
         1,  2,  3,  1,  2,  3,  1,  2,  1,  2,  1,  2,  1,  2,  1,  2,
         1,  2,  1,  2,  1,  2,  1,  2,  1,  2,  1,  1,  1,  1,  1,  1,
         1,  1,  1,  1,  1,  1,  1,  1,  1
    };

    private static final int[] AC_COURSE = {
         0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,  1,  1,  1,  1,  1,  1,  1,  1,
         1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  2,  2,  2,  2,
         2,  2,  2,  2,  2,  2,  2,  3,  3,  3,  3,  3,  4,  4,  4,  4,
         5,  5,  5,  6,  6,  6,  7,  7,  8,  8,  9,  9, 10, 10, 11, 11,
        12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 29, 30, 31
    };

    private static final int[] DC_LUM_CODE = { 0x4, 0x0, 0x1, 0x5, 0x6, 0xe, 0x1e, 0x3e, 0x7e, 0xfe, 0x1fe, 0x1ff };
    private static final int[] DC_LUM_BITS = { 3, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 9 };
    private static final int[] DC_CHR_CODE = { 0x0, 0x1, 0x2, 0x6, 0xe, 0x1e, 0x3e, 0x7e, 0xfe, 0x1fe, 0x3fe, 0x3ff };
    private static final int[] DC_CHR_BITS = { 2, 2, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10 };

    private static final int[] ZIGZAG = {
         0,  1,  8, 16,  9,  2,  3, 10, 17, 24, 32, 25, 18, 11,  4,  5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13,  6,  7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    };

    /* Le premier terme vaut toujours 16, non mis à l'échelle. */
    private static final int[] QUANT = {
        16, 16, 19, 22, 26, 27, 29, 34,
        16, 16, 22, 24, 27, 29, 34, 37,
        19, 22, 26, 27, 29, 34, 34, 38,
        22, 22, 26, 27, 29, 34, 37, 40,
        22, 26, 27, 29, 32, 35, 40, 48,
        26, 27, 29, 32, 35, 40, 48, 58,
        26, 27, 29, 34, 38, 46, 56, 69,
        27, 29, 35, 38, 46, 56, 69, 83
    };

    /*
     * Tables de lecture directe : indexées par les 16 prochains bits (lus du
     * poids faible au poids fort, comme le flux), elles donnent
     * (longueur << 16) | symbole. Longueur 0 : code inconnu.
     */
    private static final int FIN = 1000, ECHAP = 1001;
    private static final int[] T_AC = new int[1 << 16];
    private static final int[] T_DC_LUM = new int[1 << 16];
    private static final int[] T_DC_CHR = new int[1 << 16];
    private static final int[] T_ALPHA_COURSE = new int[1 << 16];
    private static final int[] T_ALPHA_NIVEAU = new int[1 << 16];

    private static final float[][] COS = new float[8][8];

    static {
        for (int i = 0; i < AC_VLC.length; i++) {
            int sym = i < 121 ? i : (i == 121 ? ECHAP : FIN);
            remplir(T_AC, AC_VLC[i][0], AC_VLC[i][1], sym);
        }
        // Les codes DC sont écrits poids fort d'abord, comme en MPEG-2 : le
        // premier bit lu est leur bit de poids fort, on les retourne.
        for (int i = 0; i < 12; i++) {
            remplir(T_DC_LUM, retourner(DC_LUM_CODE[i], DC_LUM_BITS[i]), DC_LUM_BITS[i], i);
            remplir(T_DC_CHR, retourner(DC_CHR_CODE[i], DC_CHR_BITS[i]), DC_CHR_BITS[i], i);
        }
        // Alpha, déjà dans l'ordre de lecture. Course : 0 → 0 ; 10xx → xx+1 ;
        // 111xxxxxxx → xxxxxxx ; 110 → fin (symbole 255).
        remplir(T_ALPHA_COURSE, 0, 1, 0);
        for (int i = 0; i < 4; i++) remplir(T_ALPHA_COURSE, (i << 2) | 1, 4, i + 1);
        for (int i = 0; i < 128; i++) remplir(T_ALPHA_COURSE, (i << 3) | 7, 10, i);
        remplir(T_ALPHA_COURSE, 3, 3, 255);
        // Niveau (octet signé) : 1s → ±1 ; 01sxx → ±(xx+2) ; 00xxxxxxxx → xxxxxxxx.
        for (int s = 0; s <= 1; s++) {
            remplir(T_ALPHA_NIVEAU, (s << 1) | 1, 2, (s == 1 ? -1 : 1) & 0xff);
            for (int i = 0; i < 4; i++)
                remplir(T_ALPHA_NIVEAU, (i << 3) | (s << 2) | 2, 5, (s == 1 ? -(i + 2) : i + 2) & 0xff);
        }
        for (int i = 0; i < 256; i++) remplir(T_ALPHA_NIVEAU, i << 2, 10, i);

        for (int x = 0; x < 8; x++)
            for (int u = 0; u < 8; u++)
                COS[x][u] = (float) ((u == 0 ? Math.sqrt(0.5) : 1.0) * Math.cos((2 * x + 1) * u * Math.PI / 16) / 2);
    }

    private static int retourner(int code, int n) {
        int r = 0;
        for (int i = 0; i < n; i++) if ((code & (1 << i)) != 0) r |= 1 << (n - 1 - i);
        return r;
    }

    private static void remplir(int[] t, int code, int n, int sym) {
        for (int haut = 0; haut < (1 << (16 - n)); haut++) t[(haut << n) | code] = (n << 16) | sym;
    }

    /* ------------------------------ lecture ------------------------------ */

    /** Lecteur de bits petit-boutiste, borné à une tranche. */
    private static final class Bits {
        final byte[] b; int pos; final int fin; long cache; int n;
        Bits(byte[] b, int debut, int fin) { this.b = b; pos = debut; this.fin = fin; }
        int voir(int k) {
            while (n < k) {
                long o = pos < fin ? (b[pos] & 0xff) : 0;
                pos++;
                cache |= o << n;
                n += 8;
            }
            return (int) (cache & ((1L << k) - 1));
        }
        void passer(int k) { cache >>>= k; n -= k; }
        int lire(int k) { int v = voir(k); passer(k); return v; }
        boolean deborde() { return pos - n / 8 > fin + 4; }
    }

    static final class Erreur extends Exception { Erreur(String m) { super(m); } }

    private static int symbole(Bits g, int[] t) throws Erreur {
        int e = t[g.voir(16)];
        int n = e >>> 16;
        if (n == 0) throw new Erreur("code inconnu");
        g.passer(n);
        return e & 0xffff;
    }

    /* ------------------------------ image ------------------------------ */

    public int largeur, hauteur;
    private int lA, hA;            // dimensions alignées sur 16
    private int[] y, cb, cr;       // plans décodés (chroma à pleine largeur pour 4:4:4)
    private int lC;                // largeur du plan de chrominance
    private int[] argb;
    private int sous;              // 0 : 4:2:0, 1 : 4:2:2, 2 : 4:4:4
    private int alpha;             // 0 : sans, 1 : RLE, 2 : DCT
    private final int[] quant = new int[64];
    private final float[] blocF = new float[64];
    private final int[] bloc = new int[64];

    /** Prépare le décodage d'un flux de ce FourCC (« SHQ2 »…) et de cette taille. */
    public void regler(String fourcc, int l, int h) throws Erreur {
        if (fourcc.length() != 4 || !fourcc.startsWith("SHQ")) throw new Erreur("format " + fourcc);
        switch (fourcc.charAt(3)) {
            case '0': sous = 0; alpha = 0; break;
            case '1': sous = 0; alpha = 1; break;
            case '2': sous = 1; alpha = 0; break;
            case '3': sous = 1; alpha = 1; break;
            case '4': sous = 2; alpha = 0; break;
            case '5': sous = 2; alpha = 1; break;
            case '7': sous = 1; alpha = 2; break;
            case '9': sous = 2; alpha = 2; break;
            default: throw new Erreur("format " + fourcc);
        }
        if (l < 8 || l % 8 != 0 || h < 1 || l > 4096 || h > 4096) throw new Erreur("taille " + l + "×" + h);
        if (l == largeur && h == hauteur && y != null) return;
        largeur = l; hauteur = h;
        lA = (l + 15) & ~15; hA = (h + 15) & ~15;
        lC = sous == 2 ? lA : lA / 2;
        y = new int[lA * hA];
        cb = new int[lC * hA];
        cr = new int[lC * hA];
        argb = new int[l * h];
    }

    /** Décode une image ; renvoie les pixels ARGB (tableau réutilisé d'une image à l'autre). */
    public int[] decoder(byte[] buf, int debut, int taille) throws Erreur {
        if (y == null) throw new Erreur("format non réglé");
        if (taille < 4) throw new Erreur("image vide");
        int qualite = buf[debut] & 0xff;
        if (qualite >= 100) throw new Erreur("qualité " + qualite);
        for (int i = 0; i < 64; i++) quant[i] = QUANT[ZIGZAG[i]] * (100 - qualite);
        int second = lire24(buf, debut + 1);
        if (second >= taille - 3) throw new Erreur("second champ hors image");

        if (second == 4 || second == taille - 4) {
            for (int t = 0; t < 4; t++) champ(buf, debut, taille, 0, 4, taille, 1, t);
        } else {
            for (int t = 0; t < 4; t++) champ(buf, debut, taille, 0, 4, second, 2, t);
            for (int t = 0; t < 4; t++) champ(buf, debut, taille, 1, second, taille, 2, t);
        }
        convertir();
        return argb;
    }

    private static int lire24(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16);
    }

    private void champ(byte[] buf, int base, int taille, int numChamp, int debut, int fin,
                       int pas, int tranche) throws Erreur {
        if (fin < debut || fin - debut < 3 || fin > taille) throw new Erreur("tranche");
        int[] off = new int[5];
        off[0] = debut; off[4] = fin;
        for (int x = 1; x < 4; x++) {
            int lg = lire24(buf, base + off[x - 1]);
            off[x] = off[x - 1] + lg;
            if (lg < 3 || off[x] > fin - 3) throw new Erreur("tranche");
        }
        Bits g = new Bits(buf, base + off[tranche] + 3, base + off[tranche + 1]);
        int[] dc = new int[4];
        int[] dernierAlpha = new int[16];

        int hMax = hauteur;
        for (int ly = tranche * 16 * pas; ly < hMax; ly += pas * 64) {
            dc[0] = dc[1] = dc[2] = dc[3] = 1024;
            int ligne = ly + numChamp;
            int ligneC = sous == 0 ? ly / 2 + numChamp : ligne;
            int limite = largeur - (sous != 2 ? 8 : 0);
            for (int x = 0; x < limite; x += 16) {
                macrobloc(g, dc, ligne, ligneC, x, pas);
                if (alpha != 0) passerAlpha(g, dc, dernierAlpha, ly == tranche * 16 * pas && x == 0);
            }
            if (g.deborde()) throw new Erreur("tranche tronquée");
        }
        if (sous != 2 && (largeur & 15) != 0 && tranche == 3) {
            // Bord droit : une colonne de 8 pixels, codée à la fin de la tranche 3.
            for (int ly = 0; ly < hMax; ly += 16 * pas) {
                dc[0] = dc[1] = dc[2] = dc[3] = 1024;
                int ligne = ly + numChamp;
                int ligneC = sous == 0 ? ly / 2 + numChamp : ligne;
                int x = largeur - 8;
                bloc(g, dc, 0, y, lA, ligne, x, pas);
                bloc(g, dc, 0, y, lA, ligne, x + 8, pas);   // hors image : largeur alignée
                bloc(g, dc, 0, y, lA, ligne + 8 * pas, x, pas);
                bloc(g, dc, 0, y, lA, ligne + 8 * pas, x + 8, pas);
                bloc(g, dc, 1, cb, lC, ligneC, x / 2, pas);
                bloc(g, dc, 2, cr, lC, ligneC, x / 2, pas);
                if (sous != 0) {
                    bloc(g, dc, 1, cb, lC, ligneC + 8 * pas, x / 2, pas);
                    bloc(g, dc, 2, cr, lC, ligneC + 8 * pas, x / 2, pas);
                }
                if (alpha != 0) passerAlpha(g, dc, new int[16], true);
            }
        }
    }

    private void macrobloc(Bits g, int[] dc, int ligne, int ligneC, int x, int pas) throws Erreur {
        bloc(g, dc, 0, y, lA, ligne, x, pas);
        bloc(g, dc, 0, y, lA, ligne, x + 8, pas);
        bloc(g, dc, 0, y, lA, ligne + 8 * pas, x, pas);
        bloc(g, dc, 0, y, lA, ligne + 8 * pas, x + 8, pas);
        int xc = sous == 2 ? x : x / 2;
        bloc(g, dc, 1, cb, lC, ligneC, xc, pas);
        bloc(g, dc, 2, cr, lC, ligneC, xc, pas);
        if (sous != 0) {
            bloc(g, dc, 1, cb, lC, ligneC + 8 * pas, xc, pas);
            bloc(g, dc, 2, cr, lC, ligneC + 8 * pas, xc, pas);
            if (sous == 2) {
                bloc(g, dc, 1, cb, lC, ligneC, xc + 8, pas);
                bloc(g, dc, 2, cr, lC, ligneC, xc + 8, pas);
                bloc(g, dc, 1, cb, lC, ligneC + 8 * pas, xc + 8, pas);
                bloc(g, dc, 2, cr, lC, ligneC + 8 * pas, xc + 8, pas);
            }
        }
    }

    /** L'alpha n'est pas affiché, mais il faut le lire pour rester calé dans le flux. */
    private void passerAlpha(Bits g, int[] dc, int[] dernier, boolean debut) throws Erreur {
        if (alpha == 2) {
            for (int k = 0; k < 4; k++) bloc(g, dc, 3, null, 0, 0, 0, 0);
        } else {
            for (int k = 0; k < 2; k++) {
                int i = 0;
                while (true) {
                    int course = symbole(g, T_ALPHA_COURSE);
                    if (course == 255) break;
                    i += course;
                    if (i >= 128) throw new Erreur("alpha");
                    symbole(g, T_ALPHA_NIVEAU);
                    i++;
                }
            }
        }
    }

    /** Un bloc 8×8 : DC, coefficients AC, IDCT, écrit dans plan à (ligne, x) avec ce pas de ligne. */
    private void bloc(Bits g, int[] dc, int comp, int[] plan, int larg, int ligne, int x, int pas) throws Erreur {
        java.util.Arrays.fill(bloc, 0);
        int code = symbole(g, comp == 0 || comp == 3 ? T_DC_LUM : T_DC_CHR);
        int diff = 0;
        if (code != 0) {
            int v = g.lire(code);
            diff = (v & (1 << (code - 1))) != 0 ? v : v - (1 << code) + 1;
        }
        dc[comp] -= diff;             // à l'inverse de la plupart des codecs
        bloc[0] = dc[comp];

        int i = 0;
        while (true) {
            int s = symbole(g, T_AC);
            int niveau;
            if (s == FIN) break;
            if (s == ECHAP) {
                i += g.lire(6) + 1;
                niveau = g.lire(12) - 2048;
            } else {
                i += AC_COURSE[s] + 1;
                niveau = AC_NIVEAU[s];
                if (g.lire(1) == 1) niveau = -niveau;
            }
            if (i > 63) throw new Erreur("bloc");
            bloc[ZIGZAG[i]] = (niveau * quant[i]) >> 4;
        }
        if (plan == null) return;
        idct(plan, larg, ligne, x, pas);
    }

    private void idct(int[] plan, int larg, int ligne, int x, int pas) {
        float[] t = blocF;
        // lignes
        for (int r = 0; r < 8; r++) {
            int o = r * 8;
            boolean vide = true;
            for (int u = 1; u < 8; u++) if (bloc[o + u] != 0) { vide = false; break; }
            if (vide) {
                float v = bloc[o] * COS[0][0];
                for (int k = 0; k < 8; k++) t[o + k] = v;
                continue;
            }
            for (int k = 0; k < 8; k++) {
                float s = 0;
                float[] c = COS[k];
                for (int u = 0; u < 8; u++) s += c[u] * bloc[o + u];
                t[o + k] = s;
            }
        }
        // colonnes
        for (int k = 0; k < 8; k++) {
            for (int r = 0; r < 8; r++) {
                float s = 0;
                float[] c = COS[r];
                for (int v = 0; v < 8; v++) s += c[v] * t[v * 8 + k];
                int p = Math.round(s);
                int l = ligne + r * pas;
                if (l < hA) plan[l * larg + x + k] = p < 0 ? 0 : (p > 255 ? 255 : p);
            }
        }
    }

    /** Y'CbCr BT.601 à excursion réduite (convention NDI) vers ARGB. */
    private void convertir() {
        for (int ly = 0; ly < hauteur; ly++) {
            int lc = sous == 0 ? ly / 2 : ly;
            for (int lx = 0; lx < largeur; lx++) {
                int xc = sous == 2 ? lx : lx / 2;
                int Y = (y[ly * lA + lx] - 16) * 298;
                int U = cb[lc * lC + xc] - 128, V = cr[lc * lC + xc] - 128;
                int r = (Y + 409 * V + 128) >> 8;
                int gg = (Y - 100 * U - 208 * V + 128) >> 8;
                int b = (Y + 516 * U + 128) >> 8;
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                argb[ly * largeur + lx] = 0xff000000 | (r << 16) | (gg << 8) | b;
            }
        }
    }
}
